import { expect, test } from 'bun:test';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { versionPlan, validateCandidate, stagingSucceeded, authoritativeStaging, releaseTag } from './release.mjs';

test('selected releases use Conventional Commit semantics, not a handwritten parser', async () => {
  for (const [message, version] of [['fix: repair retries', '1.2.4'], ['feat: add endpoint', '1.3.0'],
    ['feat(api)!: replace endpoint', '2.0.0'], ['fix: remove field\n\nBREAKING CHANGE: migrate clients', '2.0.0']]) {
    expect((await versionPlan([message], '1.2.3', [], 'stable')).version).toBe(version);
  }
});

test('first version is explicit, RC numbering is numeric and stable tags are never reused', async () => {
  await expect(versionPlan(['feat: add endpoint'], null, [], 'rc')).rejects.toThrow('explicit normal version');
  expect((await versionPlan(['feat: add endpoint'], null, [], 'rc', '0.1.0')).version).toBe('0.1.0-rc.1');
  expect((await versionPlan(['fix: repair retry'], '1.2.3', ['v1.2.4-rc.9', 'v1.2.4-rc.10'], 'rc')).version).toBe('1.2.4-rc.11');
  await expect(versionPlan(['fix: repair retry'], '1.2.3', ['v1.2.4'], 'stable')).rejects.toThrow('already exists');
  await expect(versionPlan([], '1.2.3', [], 'stable')).rejects.toThrow('No changes');
});

test('development targets are explicit, monotonic and cannot accidentally declare a stable API', async () => {
  await expect(versionPlan(['feat(api)!: replace endpoint'], '0.1.0', [], 'rc')).rejects.toThrow('explicit normal version');
  expect((await versionPlan(['feat(api)!: replace endpoint'], '0.1.0', [], 'rc', '0.2.0')).version).toBe('0.2.0-rc.1');
  expect((await versionPlan(['feat(api)!: replace endpoint'], '0.1.0', [], 'rc', '1.0.0')).version).toBe('1.0.0-rc.1');
  for (const target of ['0.1.0', '0.0.9', '0.2.0-rc.1', '0.2.0+metadata']) {
    await expect(versionPlan(['fix: repair retry'], '0.1.0', [], 'stable', target)).rejects.toThrow();
  }
  await expect(versionPlan(['fix: repair retry'], '1.0.0', [], 'stable', '2.0.0')).rejects.toThrow('overrides');
});

const repository = 'pipesor-org/pipesor-recommendation-service';
const candidate = { schema_version: 1, source_sha: 'a'.repeat(40), image: `ghcr.io/${repository}`,
  digest: `sha256:${'b'.repeat(64)}`, build_run_id: '42', build_run_attempt: '2' };
const run = { id: 42, run_attempt: 2, repository: { full_name: repository }, head_sha: candidate.source_sha,
  event: 'push', head_branch: 'main', path: '.github/workflows/ci.yml', status: 'completed', conclusion: 'success' };

test('release candidate identity must match successful historical CI evidence', () => {
  expect(() => validateCandidate(candidate, run, repository)).not.toThrow();
  for (const update of [{ conclusion: 'failure' }, { run_attempt: 3 }, { event: 'pull_request' },
    { head_sha: 'c'.repeat(40) }, { repository: { full_name: 'other/repo' } }]) {
    expect(() => validateCandidate(candidate, { ...run, ...update }, repository)).toThrow();
  }
});

test('stable gate requires the exact digest and latest successful preprod status', () => {
  const deployment = { environment: 'preprod', sha: candidate.source_sha,
    payload: { image: `${candidate.image}@${candidate.digest}`, validation_scope: 'candidate' } };
  const success = { created_at: '2026-11-08T10:00:00Z', state: 'success' };
  expect(stagingSucceeded(deployment, [success], candidate)).toBe(true);
  expect(stagingSucceeded(deployment, [success, { created_at: '2026-11-08T11:00:00Z', state: 'failure' }], candidate)).toBe(false);
  expect(stagingSucceeded({ ...deployment, payload: { image: 'other' } }, [success], candidate)).toBe(false);
  expect(stagingSucceeded({ ...deployment, environment: 'dev' }, [success], candidate)).toBe(false);
  expect([{ ...deployment, payload: 'deploy' }, deployment].some(item => stagingSucceeded(item, [success], candidate))).toBe(true);
  expect(stagingSucceeded({ ...deployment, payload: JSON.stringify(deployment.payload) }, [success], candidate)).toBe(true);
});

test('only scoped validation retains acceptance after inactivity and requires unambiguous history', () => {
  const success = { created_at: '2026-11-08T10:00:00Z', state: 'success' };
  const inactive = { created_at: '2026-11-08T11:00:00Z', state: 'inactive' };
  const deployment = { environment: 'preprod', sha: candidate.source_sha,
    payload: { image: `${candidate.image}@${candidate.digest}`, validation_scope: 'candidate' } };
  expect(stagingSucceeded(deployment, [inactive, success], candidate)).toBe(true);
  expect(authoritativeStaging([{ ...deployment, id: 1 }], candidate).id).toBe(1);
  expect(() => authoritativeStaging([deployment, { ...deployment, id: 2 }], candidate)).toThrow('authoritative');
  expect(() => authoritativeStaging([], candidate)).toThrow('authoritative');
  expect(stagingSucceeded({ ...deployment, payload: { image: deployment.payload.image } }, [inactive, success], candidate)).toBe(false);
  for (const state of ['failure', 'error', 'in_progress', 'pending', 'unknown']) {
    expect(stagingSucceeded(deployment, [inactive, { created_at: '2026-11-08T10:30:00Z', state }, success], candidate)).toBe(false);
  }
  expect(stagingSucceeded(deployment, [inactive], candidate)).toBe(false);
  expect(stagingSucceeded(deployment, [inactive, { ...success, created_at: inactive.created_at }], candidate)).toBe(false);
  expect(stagingSucceeded(deployment, [inactive, { ...success, created_at: 'invalid' }], candidate)).toBe(false);
});

test('accepted release references exclude aliases, build metadata and malformed RC counters', () => {
  for (const value of ['v0.1.0', 'v1.2.3-rc.1']) expect(releaseTag(value)).toBeTruthy();
  for (const value of ['latest', '1.2.3', 'v01.2.3', 'v1.2.3+build', 'v1.2.3-rc.0', 'v1.2.3-rc.01',
    'v1.2.3-rc.1;touch file']) expect(releaseTag(value)).toBeFalsy();
});

test('local CLI plans the real commit while disabled publication cannot create tags', () => {
  const directory = mkdtempSync(join(tmpdir(), 'recommendation-release-check-'));
  try {
    const empty = join(directory, 'empty');
    mkdirSync(empty);
    const git = (...args) => execFileSync('git', args, { cwd: directory, encoding: 'utf8' }).trim();
    git('init', '--quiet', '--template', empty);
    git('-c', 'user.name=Local Test', '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false',
      'commit', '--quiet', '--allow-empty', '-m', 'feat: introduce endpoint');
    const source = git('rev-parse', 'HEAD');
    git('update-ref', 'refs/remotes/origin/main', source);
    const candidatePath = join(directory, 'input.json');
    const runPath = join(directory, 'run.json');
    writeFileSync(candidatePath, JSON.stringify({ ...candidate, source_sha: source }));
    writeFileSync(runPath, JSON.stringify({ ...run, head_sha: source }));
    const script = new URL('./release.mjs', import.meta.url).pathname;
    const args = [script, 'plan', '--candidate', candidatePath, '--run', runPath,
      '--version', '0.1.0', '--output', join(directory, 'output')];
    const options = { cwd: directory, encoding: 'utf8', env: { ...process.env, GITHUB_REPOSITORY: repository,
      ACT: 'true', RELEASE_ENABLED: 'false', CANDIDATE_RELEASE: '' } };
    expect(spawnSync(process.execPath, args, options).status).toBe(0);
    const manifest = JSON.parse(readFileSync(join(directory, 'output/candidate.json')));
    expect(manifest.digest).toBe(candidate.digest);
    expect(manifest.release_version).toBe('0.1.0-rc.1');
    args[1] = 'publish';
    expect(spawnSync(process.execPath, args, options).status).toBe(1);
    expect(git('tag', '--list')).toBe('');
    writeFileSync(candidatePath, JSON.stringify({ ...candidate, source_sha: source, release_version: '0.1.0-rc.1' }));
    options.env.CANDIDATE_RELEASE = 'v0.1.0-rc.1';
    const retainedArgs = [script, 'plan', '--candidate', candidatePath, '--run', runPath,
      '--kind', 'stable', '--version', '0.1.0', '--output', join(directory, 'output')];
    expect(spawnSync(process.execPath, retainedArgs, options).status).toBe(0);
    retainedArgs[retainedArgs.indexOf('0.1.0')] = '0.2.0';
    expect(spawnSync(process.execPath, retainedArgs, options).status).toBe(1);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

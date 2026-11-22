// Releases name existing candidates; they never build or modify registry images.
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';
import load from '@commitlint/load';
import parse from '@commitlint/parse';
import semver from 'semver';

function requireThat(condition, message) {
  if (!condition) throw new Error(message);
}

function command(program, args) {
  return execFileSync(program, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

export function releaseTag(tag) {
  const version = tag?.startsWith('v') ? tag.slice(1) : '';
  const parsed = semver.parse(version);
  return parsed && semver.valid(version) === version && !parsed.build.length
    && (!parsed.prerelease.length || (parsed.prerelease.length === 2
      && parsed.prerelease[0] === 'rc' && Number.isSafeInteger(parsed.prerelease[1])
      && parsed.prerelease[1] > 0));
}

export function validateCandidate(candidate, run, repository) {
  requireThat(candidate?.schema_version === 1 && /^[a-f0-9]{40}$/.test(candidate.source_sha)
    && candidate.image === `ghcr.io/${repository.toLowerCase()}`
    && /^sha256:[a-f0-9]{64}$/.test(candidate.digest), 'Invalid candidate identity');
  requireThat(['build_run_id', 'build_run_attempt'].every(key =>
    typeof candidate[key] === 'string' && /^[1-9][0-9]*$/.test(candidate[key])), 'Invalid candidate run');
  requireThat(String(run.id) === candidate.build_run_id
    && String(run.run_attempt) === candidate.build_run_attempt
    && run.repository?.full_name === repository && run.head_sha === candidate.source_sha
    && run.event === 'push' && run.head_branch === 'main' && run.path === '.github/workflows/ci.yml'
    && run.status === 'completed' && run.conclusion === 'success', 'Candidate CI evidence does not match');
}

export async function versionPlan(messages, base, tags, kind, requestedVersion = '') {
  requireThat(['rc', 'stable'].includes(kind), 'Release kind must be rc or stable');
  const configuration = await load();
  const commits = await Promise.all(messages.map(message => parse(message, undefined, configuration.parserPreset?.parserOpts)));
  requireThat(commits.length > 0, 'No changes since the last stable release');
  const bump = commits.some(commit => commit.notes.length) ? 'major'
    : commits.some(commit => commit.type === 'feat') ? 'minor' : 'patch';
  let target;
  if (base) requireThat(releaseTag(`v${base}`) && !semver.prerelease(base), 'Invalid stable base');
  const development = !base || semver.major(base) === 0;
  if (development) {
    requireThat(releaseTag(`v${requestedVersion}`) && !semver.prerelease(requestedVersion),
      'Initial development requires an explicit normal version target');
    requireThat(!base || semver.gt(requestedVersion, base), 'Version target must exceed the stable base');
    target = requestedVersion;
  } else {
    requireThat(!requestedVersion, 'Version overrides are only allowed during initial development');
    target = semver.inc(base, bump);
  }
  requireThat(!tags.includes(`v${target}`), 'Stable version already exists');
  const attempts = tags.filter(tag => releaseTag(tag) && tag.startsWith(`v${target}-rc.`))
    .map(tag => semver.prerelease(tag.slice(1))[1]);
  const version = kind === 'rc' ? `${target}-rc.${Math.max(0, ...attempts) + 1}` : target;
  requireThat(releaseTag(`v${version}`) && !tags.includes(`v${version}`), 'Release version already exists or is invalid');
  return { version, base: base || null, bump: development ? 'explicit-development-version' : bump, commit_impact: bump,
    notes: commits.map(commit => `- ${commit.header}`).join('\n') };
}

function stagingPayload(deployment, candidate) {
  let payload = deployment.payload;
  if (typeof payload === 'string') {
    try { payload = JSON.parse(payload); } catch { return null; }
  }
  return deployment.environment === 'preprod' && deployment.sha === candidate.source_sha
    && payload?.image === `${candidate.image}@${candidate.digest}`
    && payload?.validation_scope === 'candidate' ? payload : null;
}

export function authoritativeStaging(deployments, candidate) {
  const matches = deployments.filter(deployment => stagingPayload(deployment, candidate));
  requireThat(matches.length === 1, 'One authoritative candidate validation record is required');
  return matches[0];
}

export function stagingSucceeded(deployment, statuses, candidate) {
  const payload = stagingPayload(deployment, candidate);
  if (!payload) return false;
  if (!Array.isArray(statuses) || !statuses.length
    || statuses.some(status => !Number.isFinite(Date.parse(status.created_at)))) return false;
  const history = [...statuses].sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at));
  if (history.some((status, index) => index > 0 && Date.parse(status.created_at) === Date.parse(history[index - 1].created_at)
    && status.state !== history[index - 1].state)) return false;
  if (history[0].state === 'success') return true;
  // Only explicit candidate-scoped evidence separates inactivity from validation revocation.
  if (history[0].state !== 'inactive' || payload?.validation_scope !== 'candidate') return false;
  return history.find(status => status.state !== 'inactive')?.state === 'success';
}

function ghPages(endpoint) {
  return JSON.parse(command('gh', ['api', '--paginate', '--slurp', endpoint])).flat();
}

export async function prepare(candidate, run, kind, requestedVersion, repository) {
  requireThat(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository || ''), 'Invalid repository');
  validateCandidate(candidate, run, repository);
  command('git', ['merge-base', '--is-ancestor', candidate.source_sha, 'origin/main']);
  const tags = command('git', ['tag', '--list']).split('\n').filter(Boolean);
  const stable = tags.filter(tag => releaseTag(tag) && !semver.prerelease(tag.slice(1)))
    .sort((a, b) => semver.rcompare(a.slice(1), b.slice(1)))[0];
  if (stable) command('git', ['merge-base', '--is-ancestor', stable, candidate.source_sha]);
  const history = command('git', ['log', '--reverse', '--format=%B%x00', stable
    ? `${stable}..${candidate.source_sha}` : candidate.source_sha]).split('\0').map(value => value.trim()).filter(Boolean);
  return versionPlan(history, stable?.slice(1), tags, kind, requestedVersion);
}

async function main() {
  const { values, positionals } = parseArgs({ allowPositionals: true, options: {
    candidate: { type: 'string' }, run: { type: 'string' }, kind: { type: 'string', default: 'rc' },
    version: { type: 'string', default: '' }, output: { type: 'string', default: '.delivery/release' },
  } });
  requireThat(['plan', 'publish'].includes(positionals[0]) && positionals.length === 1, 'Use plan or publish');
  const candidate = JSON.parse(readFileSync(values.candidate, 'utf8'));
  const run = JSON.parse(readFileSync(values.run, 'utf8'));
  const repository = process.env.GITHUB_REPOSITORY;
  const plan = await prepare(candidate, run, values.kind, values.version, repository);
  const retainedTag = process.env.CANDIDATE_RELEASE;
  if (retainedTag) {
    requireThat(releaseTag(retainedTag) && candidate.release_version === retainedTag.slice(1), 'Retained release manifest/version mismatch');
    if (values.kind === 'stable') {
      const prior = semver.parse(candidate.release_version);
      requireThat(plan.version === `${prior.major}.${prior.minor}.${prior.patch}`, 'Stable version must match the selected RC core version');
    }
  }
  const output = resolve(values.output);
  mkdirSync(output, { recursive: true });
  const manifest = { ...candidate, release_version: plan.version, release_base: plan.base };
  writeFileSync(`${output}/candidate.json`, `${JSON.stringify(manifest, null, 2)}\n`);
  writeFileSync(`${output}/notes.md`, `Artifact: ${candidate.image}@${candidate.digest}\n\nSource: ${candidate.source_sha}\n\n${plan.notes}\n`);
  writeFileSync(`${output}/plan.json`, `${JSON.stringify(plan, null, 2)}\n`);
  if (positionals[0] === 'plan') {
    console.log(`Proposed v${plan.version}; existing digest ${candidate.digest}; no remote writes`);
    return;
  }
  requireThat(process.env.ACT !== 'true' && process.env.RELEASE_ENABLED === 'true'
    && process.env.GITHUB_EVENT_NAME === 'workflow_dispatch' && process.env.GITHUB_REF === 'refs/heads/main',
  'Release publication is not enabled/authorized');
  requireThat(process.env.CONFIRM_IMAGE === `${candidate.image}@${candidate.digest}`
    && new RegExp(`^https://github\\.com/${repository.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/(?:issues|pull)/[1-9][0-9]*(?:#[A-Za-z0-9_-]+)?$`)
      .test(process.env.AUTHORIZATION_REF || ''), 'Explicit image and authorization reference required');
  if (values.kind === 'stable') {
    // Existing release policy remains: dev readiness alone cannot authorize stable publication.
    const deployments = ghPages(`repos/${repository}/deployments?environment=preprod&sha=${candidate.source_sha}&per_page=100`);
    const validation = authoritativeStaging(deployments, candidate);
    requireThat(stagingSucceeded(validation,
      ghPages(`repos/${repository}/deployments/${validation.id}/statuses?per_page=100`), candidate),
    'Stable release requires retained successful staging validation for this exact digest');
  }
  // Ref creation rejects collisions atomically. Partial publication requires inspection, never overwrite.
  const tag = `v${plan.version}`;
  command('gh', ['api', '--method', 'POST', `repos/${repository}/git/refs`,
    '-f', `ref=refs/tags/${tag}`, '-f', `sha=${candidate.source_sha}`]);
  const args = ['release', 'create', tag, '--repo', repository, '--verify-tag', '--draft',
    '--title', tag, '--notes-file', `${output}/notes.md`, `${output}/candidate.json`];
  if (values.kind === 'rc') args.push('--prerelease');
  command('gh', args);
  command('gh', ['release', 'edit', tag, '--repo', repository, '--draft=false', '--latest=false']);
  console.log(`Published ${tag}: existing digest ${candidate.digest}; no rebuild or registry mutation`);
}

if (import.meta.main) main().catch(() => {
  console.error('Release rejected or failed; inspect plan, candidate, authorization and any partial tag/draft before retry.');
  process.exitCode = 1;
});

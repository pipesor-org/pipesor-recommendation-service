"""Validate a published candidate and update one existing, explicitly authorized ACA app."""

import argparse
import json
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID


def require(condition, message):
    if not condition:
        raise ValueError(message)


def operation(env):
    selected = env.get("DEPLOYMENT_OPERATION", "deploy")
    require(selected in ("deploy", "rollback"), "Invalid deployment operation")
    selectors = [env.get(key, "") for key in ("ROLLBACK_RUN_ID", "ROLLBACK_RUN_ATTEMPT")]
    if selected == "rollback":
        require(all(re.fullmatch(r"[1-9][0-9]*", value) for value in selectors), "Invalid rollback run/attempt")
        require(env.get("ROLLBACK_CONFIRMED") == "true", "Rollback compatibility/acceptance confirmation is required")
    else:
        require(not any(selectors) and env.get("ROLLBACK_CONFIRMED", "false") in ("", "false"),
                "Rollback inputs require the rollback operation")
    return selected


def selection(env):
    repository = env.get("GITHUB_REPOSITORY", "")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Invalid repository")
    release = env.get("CANDIDATE_RELEASE", "")
    run_id, attempt = env.get("CANDIDATE_RUN_ID", ""), env.get("CANDIDATE_RUN_ATTEMPT", "")
    if release:
        number = r"(?:0|[1-9][0-9]*)"
        require(re.fullmatch(r"v" + number + r"\." + number + r"\." + number + r"(?:-rc\.[1-9][0-9]*)?", release),
                "Invalid release version")
        require(not run_id and not attempt, "Choose a release or a CI run, not both")
    else:
        require(all(re.fullmatch(r"[1-9][0-9]*", value) for value in (run_id, attempt)), "Invalid CI run/attempt")
    return repository, release


def gh(*args):
    result = subprocess.run(["gh", *args], text=True, capture_output=True, check=False)
    require(result.returncode == 0, "GitHub candidate retrieval failed")
    return result.stdout


def fetch_candidate(candidate_path, run_path, env):
    operation(env)
    repository, release = selection(env)
    candidate_path.parent.mkdir(parents=True, exist_ok=True)
    if release:
        metadata = json.loads(gh("api", f"repos/{repository}/releases/tags/{release}"))
        require(metadata.get("tag_name") == release and metadata.get("draft") is False,
                "Candidate release must be published")
        gh("release", "download", release, "--repo", repository, "--pattern", "candidate.json",
           "--dir", str(candidate_path.parent))
    else:
        gh("run", "download", env["CANDIDATE_RUN_ID"], "--repo", repository,
           "--name", f"candidate-{env['CANDIDATE_RUN_ID']}-{env['CANDIDATE_RUN_ATTEMPT']}",
           "--dir", str(candidate_path.parent))
    candidate = json.loads(candidate_path.read_text())
    for key in ("build_run_id", "build_run_attempt"):
        require(isinstance(candidate.get(key), str) and re.fullmatch(r"[1-9][0-9]*", candidate[key]),
                f"Invalid {key}")
    if release:
        commit = json.loads(gh("api", f"repos/{repository}/commits/{release}"))
        require(commit.get("sha") == candidate.get("source_sha"), "Release tag/candidate source mismatch")
    run_path.write_text(gh("api", f"repos/{repository}/actions/runs/{candidate['build_run_id']}"
                          f"/attempts/{candidate['build_run_attempt']}"))


def verify_delivery_run(run, env):
    require(isinstance(run, dict), "Invalid rollback delivery metadata")
    require(str(run.get("id")) == env["ROLLBACK_RUN_ID"]
            and str(run.get("run_attempt")) == env["ROLLBACK_RUN_ATTEMPT"], "Rollback producer run mismatch")
    require(run.get("repository", {}).get("full_name") == env["GITHUB_REPOSITORY"]
            and run.get("event") == "workflow_dispatch" and run.get("head_branch") == "main"
            and run.get("path") == ".github/workflows/deploy.yml", "Rollback requires this repository's main delivery workflow")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "Rollback delivery did not succeed")


def fetch_rollback(record_path, run_path, env):
    require(operation(env) == "rollback", "Rollback operation is required")
    repository, _ = selection(env)
    run_id, attempt = env["ROLLBACK_RUN_ID"], env["ROLLBACK_RUN_ATTEMPT"]
    run = json.loads(gh("api", f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}"))
    verify_delivery_run(run, env)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    gh("run", "download", run_id, "--repo", repository, "--name", f"deployment-dev-{run_id}-{attempt}",
       "--dir", str(record_path.parent))
    run_path.write_text(json.dumps(run))


def validate_rollback(record, run, request, env):
    require(isinstance(record, dict), "Invalid rollback record")
    require(type(record.get("deployment_record_version")) is int and record["deployment_record_version"] == 1,
            "Unsupported rollback record version")
    verify_delivery_run(run, env)
    require(record.get("delivery_run_id") == env["ROLLBACK_RUN_ID"]
            and record.get("delivery_run_attempt") == env["ROLLBACK_RUN_ATTEMPT"], "Rollback record/producer mismatch")
    require(isinstance(record.get("delivery_workflow_sha"), str)
            and re.fullmatch(r"[0-9a-f]{40}", record["delivery_workflow_sha"])
            and record["delivery_workflow_sha"] == run.get("head_sha"), "Rollback workflow source mismatch")
    require(record.get("status") == "aca_ready" and isinstance(record.get("revision"), str)
            and re.fullmatch(r"[a-z0-9][a-z0-9-]*", record["revision"]), "Rollback record was not ready")
    for key in ("repository", "image", "target", "environment", "container", "source_sha", "build_run_id", "build_run_attempt"):
        require(record.get(key) == request[key], f"Rollback candidate/destination mismatch: {key}")
    return {"rollback_run_id": env["ROLLBACK_RUN_ID"], "rollback_run_attempt": env["ROLLBACK_RUN_ATTEMPT"],
            "rollback_revision": record["revision"]}


def validate(candidate, run, env, rollback_record=None, rollback_run=None):
    selected_operation = operation(env)
    require(isinstance(candidate, dict) and isinstance(run, dict), "Invalid candidate/run metadata")
    require(env.get("ACA_DEPLOYMENT_ENABLED") == "true", "ACA deployment is not enabled")
    require(env.get("ACA_IMAGE_OWNER") == "native", "Native image ownership must be accepted first")
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and env.get("GITHUB_REF") == "refs/heads/main", "Deployment requires manual execution from main")
    repository, release = selection(env)
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        require(re.fullmatch(r"[1-9][0-9]*", env.get(key, "")), f"Invalid {key}")
    require(re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")), "Invalid delivery workflow SHA")
    if release:
        require(candidate.get("release_version") == release[1:], "Release version/candidate mismatch")
    for key in ("AZURE_TENANT_ID", "AZURE_SUBSCRIPTION_ID", "AZURE_CLIENT_ID"):
        value = env.get(key, "")
        try:
            require(str(UUID(value)) == value.lower(), f"Invalid {key}")
        except (ValueError, AttributeError):
            raise ValueError(f"Invalid {key}") from None
    subscription = env["AZURE_SUBSCRIPTION_ID"]
    target = env.get("ACA_RESOURCE_ID", "")
    match = re.fullmatch(r"/subscriptions/([^/]+)/resourceGroups/([A-Za-z0-9_.()-]+)/providers/Microsoft.App/containerApps/([a-z][a-z0-9-]*)", target)
    require(match is not None and match[1].lower() == subscription.lower(), "Invalid ACA resource ID/subscription")
    environment = env.get("ACA_ENVIRONMENT_ID", "")
    require(re.fullmatch(r"/subscriptions/" + re.escape(subscription)
                        + r"/resourceGroups/[A-Za-z0-9_.()-]+/providers/Microsoft.App/managedEnvironments/[A-Za-z0-9-]+", environment,
                        re.IGNORECASE), "Invalid ACA environment ID")
    container = env.get("ACA_CONTAINER_NAME", "")
    require(re.fullmatch(r"[a-z][a-z0-9-]*", container), "Missing/invalid ACA container name")
    require(type(candidate.get("schema_version")) is int and candidate["schema_version"] == 1, "Unsupported candidate schema")
    require(all(isinstance(candidate.get(key), str) for key in ("source_sha", "image", "digest")),
            "Invalid candidate field types")
    require(re.fullmatch(r"[0-9a-f]{40}", candidate.get("source_sha", "")), "Invalid candidate source SHA")
    require(candidate.get("image") == "ghcr.io/" + repository.lower(), "Candidate belongs to another image")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", candidate.get("digest", "")), "Invalid image digest")
    for key, input_key, run_key in (("build_run_id", "CANDIDATE_RUN_ID", "id"),
                                   ("build_run_attempt", "CANDIDATE_RUN_ATTEMPT", "run_attempt")):
        value = candidate.get(key, "")
        require(isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value), f"Invalid {key}")
        require(value == str(run.get(run_key)), f"Candidate/run mismatch: {key}")
        require(release or value == env.get(input_key), f"Candidate/input mismatch: {key}")
    require(run.get("event") == "push" and run.get("head_branch") == "main"
            and run.get("path") == ".github/workflows/ci.yml", "Candidate was not built by main CI")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Candidate CI did not succeed")
    require(run.get("head_sha") == candidate["source_sha"]
            and run.get("repository", {}).get("full_name") == repository, "Candidate source/run mismatch")
    image = candidate["image"] + "@" + candidate["digest"]
    require(env.get("CONFIRM_IMAGE") == image and env.get("CONFIRM_TARGET") == target,
            "Explicit image and target confirmations do not match")
    reference = env.get("AUTHORIZATION_REF", "")
    require(re.fullmatch(r"https://github\.com/" + re.escape(repository)
                        + r"/(?:issues|pull)/[1-9][0-9]*(?:#[A-Za-z0-9_-]+)?", reference),
            "A repository issue/PR authorization reference is required")
    # GitHub Free/private cannot independently enforce the human approval behind this reference.
    request = {"repository": repository, "image": image, "target": target, "environment": environment, "container": container,
            "source_sha": candidate["source_sha"], "build_run_id": candidate["build_run_id"],
            "build_run_attempt": candidate["build_run_attempt"], "candidate_release": release,
            "authorization_ref": reference, "operation": selected_operation, "deployment_record_version": 1,
            "delivery_run_id": env["GITHUB_RUN_ID"], "delivery_run_attempt": env["GITHUB_RUN_ATTEMPT"],
            "delivery_workflow_sha": env["GITHUB_SHA"]}
    if selected_operation == "rollback":
        request.update(validate_rollback(rollback_record, rollback_run, request, env))
    return request


def az(*args):
    result = subprocess.run(["az", *args, "--only-show-errors", "--output", "json"],
                            text=True, capture_output=True, check=False)
    require(result.returncode == 0, "Azure command failed; inspect the target through the authorized operational process")
    return json.loads(result.stdout) if result.stdout.strip() else None


def verify_target(app, request):
    require(app.get("id", "").lower() == request["target"].lower(), "Azure returned another resource")
    properties = app.get("properties", {})
    require(properties.get("configuration", {}).get("activeRevisionsMode") == "Single",
            "Deployment requires single-revision mode; traffic management is outside this adapter")
    environment = properties.get("environmentId") or properties.get("managedEnvironmentId", "")
    require(environment.lower() == request["environment"].lower(), "App belongs to another ACA environment")
    containers = properties.get("template", {}).get("containers", [])
    require(len(containers) == 1 and containers[0].get("name") == request["container"],
            "Deployment supports only the explicitly configured single-container app")
    return properties, containers[0]


def write_evidence(path, record):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as output:
        output.write(json.dumps(record, indent=2) + "\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def deploy(request, env, output):
    receipt = {**request, "started_at": datetime.now(timezone.utc).isoformat(),
               "status": "in_progress", "phase": "target_verification"}
    write_evidence(output, receipt)
    try:
        account = az("account", "show")
        require(account.get("id", "").lower() == env["AZURE_SUBSCRIPTION_ID"].lower()
                and account.get("tenantId", "").lower() == env["AZURE_TENANT_ID"].lower(), "Azure account mismatch")
        previous = az("containerapp", "show", "--ids", request["target"])
        properties, old_container = verify_target(previous, request)
        require(isinstance(old_container.get("image"), str) and old_container["image"], "Previous image is unavailable")
        receipt.update(previous_image=old_container.get("image"), previous_revision=properties.get("latestRevisionName"),
                       previous_ready_revision=properties.get("latestReadyRevisionName"), phase="image_update")
        # Preserve recovery evidence even when update/readiness fails; never retain full Azure responses.
        write_evidence(output.with_name("deployment-before.json"), receipt)
        write_evidence(output, receipt)
        az("containerapp", "update", "--ids", request["target"], "--container-name", request["container"],
           "--image", request["image"])
        receipt["phase"] = "readiness"
        for attempt in range(30):
            current = az("containerapp", "show", "--ids", request["target"])
            properties, container = verify_target(current, request)
            if (container.get("image") == request["image"] and properties.get("provisioningState") == "Succeeded"
                    and properties.get("latestRevisionName")
                    and properties.get("latestRevisionName") == properties.get("latestReadyRevisionName")):
                receipt.update(revision=properties["latestReadyRevisionName"], status="aca_ready")
                write_evidence(output, receipt)
                return receipt
            if attempt < 29:
                time.sleep(10)
        raise ValueError("ACA readiness was not confirmed; inspect actual state before retry or recovery")
    except (ValueError, OSError, KeyError, TypeError, AttributeError):
        receipt["status"] = "failed"
        write_evidence(output, receipt)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("fetch", "validate", "deploy"))
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--rollback-record", type=Path, default=Path(".delivery/rollback/deployment-result.json"))
    parser.add_argument("--rollback-run", type=Path, default=Path(".delivery/rollback/delivery-run.json"))
    args = parser.parse_args()
    adapter_started = False
    try:
        if args.mode == "fetch":
            fetch_candidate(args.candidate, args.run, os.environ)
            if operation(os.environ) == "rollback":
                fetch_rollback(args.rollback_record, args.rollback_run, os.environ)
            return
        require(args.output is not None, "An evidence output path is required")
        rollback_record = rollback_run = None
        if operation(os.environ) == "rollback":
            rollback_record = json.loads(args.rollback_record.read_text())
            rollback_run = json.loads(args.rollback_run.read_text())
        request = validate(json.loads(args.candidate.read_text()), json.loads(args.run.read_text()), os.environ,
                           rollback_record, rollback_run)
        result = subprocess.run(["git", "merge-base", "--is-ancestor", request["source_sha"], "origin/main"],
                                capture_output=True, check=False)
        require(result.returncode == 0, "Candidate commit is not reachable from main")
        if args.mode == "deploy":
            adapter_started = True
            deploy(request, os.environ, args.output)
        else:
            write_evidence(args.output, request)
    except (ValueError, OSError, KeyError, TypeError, AttributeError):
        if args.output and not adapter_started:
            try:
                write_evidence(args.output, {"status": "rejected", "phase": "candidate_fetch" if args.mode == "fetch" else "validation"})
            except OSError:
                pass
        # Do not print external payloads, CLI output or exception details that could contain credentials.
        parser.exit(1, "Deployment rejected or failed; check candidate, configuration, authorization and ACA readiness.\n")


if __name__ == "__main__":
    main()

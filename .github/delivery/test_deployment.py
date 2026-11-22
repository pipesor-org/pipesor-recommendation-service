import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from deployment import deploy, fetch_candidate, fetch_rollback, main, validate


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name) / "deployment-result.json"
        self.repository = "pipesor-org/pipesor-recommendation-service"
        self.uuid = "11111111-1111-4111-8111-111111111111"
        self.target = f"/subscriptions/{self.uuid}/resourceGroups/test-dev/providers/Microsoft.App/containerApps/recommendation"
        self.environment = f"/subscriptions/{self.uuid}/resourceGroups/test-dev/providers/Microsoft.App/managedEnvironments/platform"
        self.candidate = {"schema_version": 1, "source_sha": "a" * 40,
                          "image": "ghcr.io/" + self.repository, "digest": "sha256:" + "b" * 64,
                          "build_run_id": "42", "build_run_attempt": "2"}
        self.run = {"id": 42, "run_attempt": 2, "event": "push", "head_branch": "main",
                    "path": ".github/workflows/ci.yml", "status": "completed", "conclusion": "success",
                    "head_sha": self.candidate["source_sha"], "repository": {"full_name": self.repository}}
        self.env = {"ACA_DEPLOYMENT_ENABLED": "true", "ACA_IMAGE_OWNER": "native",
                    "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                    "GITHUB_RUN_ID": "100", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": "e" * 40,
                    "GITHUB_REPOSITORY": self.repository, "AZURE_TENANT_ID": self.uuid,
                    "AZURE_SUBSCRIPTION_ID": self.uuid, "AZURE_CLIENT_ID": self.uuid,
                    "ACA_RESOURCE_ID": self.target, "ACA_ENVIRONMENT_ID": self.environment,
                    "ACA_CONTAINER_NAME": "recommendation", "CANDIDATE_RUN_ID": "42", "CANDIDATE_RUN_ATTEMPT": "2",
                    "CONFIRM_IMAGE": self.candidate["image"] + "@" + self.candidate["digest"],
                    "CONFIRM_TARGET": self.target,
                    "AUTHORIZATION_REF": f"https://github.com/{self.repository}/issues/1"}

    def app(self, image, ready=True):
        return {"id": self.target, "properties": {"environmentId": self.environment,
                "configuration": {"activeRevisionsMode": "Single"},
                "template": {"containers": [{"name": "recommendation", "image": image}]},
                "provisioningState": "Succeeded", "latestRevisionName": "recommendation--new",
                "latestReadyRevisionName": "recommendation--new" if ready else "recommendation--old"}}

    def test_missing_configuration_or_authorization_is_rejected(self):
        for key in self.env:
            with self.subTest(key=key):
                env = self.env.copy()
                del env[key]
                with self.assertRaises(ValueError):
                    validate(self.candidate, self.run, env)
        for key, value in (("ACA_DEPLOYMENT_ENABLED", "false"), ("ACA_IMAGE_OWNER", "terraform"),
                           ("GITHUB_REF", "refs/heads/feature/test"), ("CONFIRM_TARGET", self.target + "-other"),
                           ("AUTHORIZATION_REF", "approved")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate(self.candidate, self.run, {**self.env, key: value})

    def test_pull_request_failed_or_mismatched_build_is_rejected(self):
        changes = {"event": "pull_request", "head_branch": "feature/test", "conclusion": "failure",
                   "status": "in_progress", "head_sha": "c" * 40, "run_attempt": 1, "id": 9,
                   "path": ".github/workflows/other.yml", "repository": {"full_name": "other/repo"}}
        for key, value in changes.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate(self.candidate, {**self.run, key: value}, self.env)

    def test_malformed_and_foreign_candidates_are_rejected(self):
        changes = {"schema_version": True, "source_sha": "main", "digest": "latest", "image": "ghcr.io/other/repo",
                   "build_run_id": "42; exit 0", "build_run_attempt": "02"}
        for key, value in changes.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate({**self.candidate, key: value}, self.run, self.env)

    @patch("deployment.gh")
    def test_release_download_preserves_original_ci_verification(self, github):
        release = "v1.2.3-rc.1"
        env = {**self.env, "CANDIDATE_RELEASE": release, "CANDIDATE_RUN_ID": "", "CANDIDATE_RUN_ATTEMPT": ""}
        self.candidate["release_version"] = release[1:]
        candidate = self.output.with_name("candidate.json")
        run = self.output.with_name("candidate-run.json")
        candidate.write_text(json.dumps(self.candidate))
        github.side_effect = [json.dumps({"tag_name": release, "draft": False}), "",
                              json.dumps({"sha": self.candidate["source_sha"]}), json.dumps(self.run)]
        fetch_candidate(candidate, run, env)
        request = validate(json.loads(candidate.read_text()), json.loads(run.read_text()), env)
        self.assertEqual(release, request["candidate_release"])
        self.assertEqual("42", request["build_run_id"])
        self.assertIn(self.repository, github.call_args_list[1].args)
        self.assertEqual(("api", f"repos/{self.repository}/actions/runs/42/attempts/2"), github.call_args_list[-1].args)
        with self.assertRaisesRegex(ValueError, "did not succeed"):
            validate(self.candidate, {**self.run, "conclusion": "failure"}, env)
        with self.assertRaisesRegex(ValueError, "version/candidate"):
            validate({**self.candidate, "release_version": "1.0.0"}, self.run, env)

    @patch("deployment.gh")
    def test_release_selection_rejects_ambiguous_foreign_and_malformed_inputs_before_download(self, github):
        for release in ("v1.0.0", "v01.0.0", "v1.0.0-rc.0", "other/repo/v1.0.0", "v1.0.0; exit 0"):
            with self.subTest(release=release), self.assertRaises(ValueError):
                fetch_candidate(self.output.with_name("candidate.json"), self.output.with_name("run.json"),
                                {**self.env, "CANDIDATE_RELEASE": release})
        github.assert_not_called()

    @patch("deployment.gh")
    def test_published_release_must_match_candidate_commit(self, github):
        env = {**self.env, "CANDIDATE_RELEASE": "v1.0.0", "CANDIDATE_RUN_ID": "", "CANDIDATE_RUN_ATTEMPT": ""}
        candidate = self.output.with_name("candidate.json")
        candidate.write_text(json.dumps(self.candidate))
        github.side_effect = [json.dumps({"tag_name": "v1.0.0", "draft": False}), "", json.dumps({"sha": "c" * 40})]
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            fetch_candidate(candidate, self.output.with_name("run.json"), env)

    @patch("deployment.az")
    def test_updates_only_existing_single_container_by_digest(self, azure):
        request = validate(self.candidate, self.run, self.env)
        azure.side_effect = [{"id": self.uuid, "tenantId": self.uuid}, self.app("old-image"), None,
                             self.app(request["image"])]
        result = deploy(request, self.env, self.output)
        self.assertEqual("aca_ready", result["status"])
        self.assertEqual("old-image", result["previous_image"])
        self.assertEqual(("containerapp", "update", "--ids", self.target, "--container-name", "recommendation",
                          "--image", request["image"]), azure.call_args_list[2].args)

    @patch("deployment.az")
    def test_explicit_rollback_restores_previously_ready_a_after_failed_b(self, azure):
        candidate_a = {**self.candidate, "source_sha": "d" * 40, "digest": "sha256:" + "c" * 64,
                       "build_run_id": "41"}
        ci_a = {**self.run, "id": 41, "head_sha": candidate_a["source_sha"]}
        image_a = candidate_a["image"] + "@" + candidate_a["digest"]
        env_a = {**self.env, "CANDIDATE_RUN_ID": "41", "CONFIRM_IMAGE": image_a,
                 "GITHUB_RUN_ID": "90", "GITHUB_SHA": "f" * 40}
        account = {"id": self.uuid, "tenantId": self.uuid}
        azure.side_effect = [account, self.app("old"), None, self.app(image_a)]
        record_a = deploy(validate(candidate_a, ci_a, env_a), env_a, self.output.with_name("prior-a.json"))
        record_a["unrelated_config"] = "must-not-be-recorded"
        delivery_a = {"id": 90, "run_attempt": 1, "event": "workflow_dispatch", "head_branch": "main",
                      "path": ".github/workflows/deploy.yml", "status": "completed", "conclusion": "success",
                      "head_sha": env_a["GITHUB_SHA"], "repository": {"full_name": self.repository}}
        request_b = validate(self.candidate, self.run, self.env)
        azure.reset_mock()
        azure.side_effect = [account, self.app(image_a), ValueError("Update failed")]
        with self.assertRaises(ValueError):
            deploy(request_b, self.env, self.output)
        failure = json.loads(self.output.read_text())
        self.assertEqual("failed", failure["status"])
        self.assertEqual([request_b["image"]], [call.args[-1] for call in azure.call_args_list
                                              if call.args[:2] == ("containerapp", "update")])

        rollback_env = {**env_a, "GITHUB_RUN_ID": "101", "GITHUB_SHA": self.env["GITHUB_SHA"],
                        "DEPLOYMENT_OPERATION": "rollback", "ROLLBACK_RUN_ID": "90",
                        "ROLLBACK_RUN_ATTEMPT": "1", "ROLLBACK_CONFIRMED": "true"}
        recovery_directory = self.output.parent / "rollback-run"
        recovery_directory.mkdir()
        # A failed update response does not prove that ACA left the old image untouched.
        azure.side_effect = [account, self.app(request_b["image"], ready=False), None, self.app(image_a)]
        candidate_path, ci_path = recovery_directory / "candidate.json", recovery_directory / "ci.json"
        record_path, delivery_path = recovery_directory / "prior.json", recovery_directory / "delivery.json"
        candidate_path.write_text(json.dumps(candidate_a))
        ci_path.write_text(json.dumps(ci_a))
        record_path.write_text(json.dumps(record_a))
        delivery_path.write_text(json.dumps(delivery_a))
        result_path = recovery_directory / "deployment-result.json"
        argv = ["deployment.py", "deploy", "--candidate", str(candidate_path), "--run", str(ci_path),
                "--output", str(result_path), "--rollback-record", str(record_path), "--rollback-run", str(delivery_path)]
        with (patch.dict("os.environ", rollback_env, clear=True), patch.object(sys, "argv", argv),
              patch("deployment.subprocess.run", return_value=subprocess.CompletedProcess([], 0))):
            main()
        result = json.loads(result_path.read_text())
        self.assertEqual("aca_ready", result["status"])
        self.assertEqual("rollback", result["operation"])
        self.assertEqual("90", result["rollback_run_id"])
        self.assertEqual(record_a["revision"], result["rollback_revision"])
        self.assertEqual("101", result["delivery_run_id"])
        self.assertEqual(image_a, result["image"])
        self.assertEqual(request_b["image"], result["previous_image"])
        self.assertNotIn("must-not-be-recorded", result_path.read_text())
        self.assertEqual(("containerapp", "update", "--ids", self.target, "--container-name", "recommendation",
                          "--image", image_a), azure.call_args_list[-2].args)
        self.assertEqual(failure, json.loads(self.output.read_text()))

    def rollback_fixture(self):
        env = {**self.env, "DEPLOYMENT_OPERATION": "rollback", "ROLLBACK_RUN_ID": "90",
               "ROLLBACK_RUN_ATTEMPT": "1", "ROLLBACK_CONFIRMED": "true"}
        record = {**validate(self.candidate, self.run, {**self.env, "GITHUB_RUN_ID": "90"}),
                  "status": "aca_ready", "revision": "recommendation--accepted-a"}
        run = {"id": 90, "run_attempt": 1, "event": "workflow_dispatch", "head_branch": "main",
               "path": ".github/workflows/deploy.yml", "status": "completed", "conclusion": "success",
               "head_sha": record["delivery_workflow_sha"], "repository": {"full_name": self.repository}}
        return env, record, run

    @patch("deployment.az")
    def test_rollback_rejects_unbound_or_unready_evidence_before_azure_in_both_modes(self, azure):
        env, record, delivery = self.rollback_fixture()
        candidate_path = self.output.with_name("candidate.json")
        ci_path = self.output.with_name("ci.json")
        record_path = self.output.with_name("rollback.json")
        delivery_path = self.output.with_name("delivery.json")
        candidate_path.write_text(json.dumps(self.candidate))
        ci_path.write_text(json.dumps(self.run))
        invalid = [
            ("target", {**record, "target": self.target + "-other"}, delivery, env),
            ("image", {**record, "image": self.candidate["image"] + "@sha256:" + "c" * 64}, delivery, env),
            ("source", {**record, "source_sha": "d" * 40}, delivery, env),
            ("ci-attempt", {**record, "build_run_attempt": "1"}, delivery, env),
            ("producer", {**record, "delivery_run_id": "89"}, delivery, env),
            ("tooling-source", {**record, "delivery_workflow_sha": "f" * 40}, delivery, env),
            ("failed", {**record, "status": "failed"}, delivery, env),
            ("not-started", {**record, "status": "not_started"}, delivery, env),
            ("revision", {**record, "revision": ""}, delivery, env),
            ("schema", {k: v for k, v in record.items() if k != "deployment_record_version"}, delivery, env),
            ("unknown-schema", {**record, "deployment_record_version": 2}, delivery, env),
            ("workflow", record, {**delivery, "path": ".github/workflows/ci.yml"}, env),
            ("foreign", record, {**delivery, "repository": {"full_name": "other/repo"}}, env),
            ("failed-run", record, {**delivery, "conclusion": "failure"}, env),
            ("producer-attempt", record, {**delivery, "run_attempt": 2}, env),
            ("missing-ack", record, delivery, {**env, "ROLLBACK_CONFIRMED": "false"}),
        ]
        for mode in ("validate", "deploy"):
            for cause, bad_record, bad_delivery, bad_env in invalid:
                with self.subTest(mode=mode, cause=cause):
                    record_path.write_text(json.dumps(bad_record))
                    delivery_path.write_text(json.dumps(bad_delivery))
                    argv = ["deployment.py", mode, "--candidate", str(candidate_path), "--run", str(ci_path),
                            "--output", str(self.output), "--rollback-record", str(record_path),
                            "--rollback-run", str(delivery_path)]
                    with patch.dict("os.environ", bad_env, clear=True), patch.object(sys, "argv", argv), \
                            patch("sys.stderr", new_callable=io.StringIO):
                        with self.assertRaises(SystemExit) as failure:
                            main()
                    self.assertEqual(1, failure.exception.code)
                    self.assertEqual("rejected", json.loads(self.output.read_text())["status"])
        azure.assert_not_called()

    @patch("deployment.gh")
    def test_rollback_fetch_binds_artifact_to_successful_same_repository_delivery_attempt(self, github):
        env, _record, delivery = self.rollback_fixture()
        record_path = self.output.parent / "rollback" / "deployment-result.json"
        run_path = record_path.with_name("delivery-run.json")
        github.side_effect = [json.dumps(delivery), ""]
        fetch_rollback(record_path, run_path, env)
        self.assertEqual(("api", f"repos/{self.repository}/actions/runs/90/attempts/1"), github.call_args_list[0].args)
        self.assertEqual(("run", "download", "90", "--repo", self.repository, "--name", "deployment-dev-90-1",
                          "--dir", str(record_path.parent)), github.call_args_list[1].args)
        self.assertEqual(delivery, json.loads(run_path.read_text()))
        github.reset_mock()
        github.side_effect = [json.dumps({**delivery, "conclusion": "failure"})]
        with self.assertRaisesRegex(ValueError, "did not succeed"):
            fetch_rollback(record_path, run_path, env)
        self.assertEqual(1, github.call_count)

    @patch("deployment.gh")
    def test_deploy_rejects_rollback_selectors_and_confirmation_before_fetch(self, github):
        for extra in ({"ROLLBACK_RUN_ID": "90"}, {"ROLLBACK_RUN_ATTEMPT": "1"},
                      {"ROLLBACK_CONFIRMED": "true"}, {"DEPLOYMENT_OPERATION": "automatic"}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                fetch_candidate(self.output.with_name("candidate.json"), self.output.with_name("ci.json"),
                                {**self.env, **extra})
        github.assert_not_called()

    @patch("deployment.az")
    def test_same_digest_ready_revision_is_success_without_new_revision(self, azure):
        request = validate(self.candidate, self.run, self.env)
        existing = self.app(request["image"])
        azure.side_effect = [{"id": self.uuid, "tenantId": self.uuid}, existing, None, existing]
        result = deploy(request, self.env, self.output)
        self.assertEqual(existing["properties"]["latestReadyRevisionName"], result["revision"])
        self.assertEqual(request["image"], result["previous_image"])

    @patch("deployment.az")
    def test_account_or_target_mismatch_stops_before_write(self, azure):
        request = validate(self.candidate, self.run, self.env)
        for change in ("account", "environment", "container", "revisions", "missing_image"):
            with self.subTest(change=change):
                app = self.app("old")
                account = {"id": self.uuid, "tenantId": self.uuid}
                if change == "account":
                    account["tenantId"] = "wrong"
                elif change == "environment":
                    app["properties"]["environmentId"] = "wrong"
                elif change == "container":
                    app["properties"]["template"]["containers"].append({"name": "another"})
                elif change == "missing_image":
                    del app["properties"]["template"]["containers"][0]["image"]
                else:
                    app["properties"]["configuration"]["activeRevisionsMode"] = "Multiple"
                azure.reset_mock()
                azure.side_effect = [account, app]
                with self.assertRaises(ValueError):
                    deploy(request, self.env, self.output)
                self.assertFalse(any("update" in call.args for call in azure.call_args_list))

    @patch("deployment.time.sleep", return_value=None)
    @patch("deployment.az")
    def test_old_ready_revision_never_counts_as_new_deployment_success(self, azure, _sleep):
        request = validate(self.candidate, self.run, self.env)
        azure.side_effect = [{"id": self.uuid, "tenantId": self.uuid}, self.app("old"), None] + [self.app(request["image"], ready=False)] * 30
        with self.assertRaisesRegex(ValueError, "readiness"):
            deploy(request, self.env, self.output)
        receipt = json.loads(self.output.read_text())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("readiness", receipt["phase"])
        self.assertEqual("old", receipt["previous_image"])
        self.assertNotIn("revision", receipt)

    @patch("deployment.az")
    def test_previous_image_is_durable_before_failed_update_without_raw_azure_data(self, azure):
        request = validate(self.candidate, self.run, self.env)
        old = self.app("old-image")
        old["properties"]["configuration"]["secrets"] = [{"name": "test", "value": "must-not-be-recorded"}]

        def respond(*args):
            if args == ("account", "show"):
                return {"id": self.uuid, "tenantId": self.uuid}
            if "update" not in args:
                return old
            evidence = self.output.with_name("deployment-before.json").read_text()
            before = json.loads(evidence)
            self.assertEqual("old-image", before["previous_image"])
            self.assertEqual("recommendation--new", before["previous_revision"])
            self.assertEqual("42", before["build_run_id"])
            self.assertNotIn("must-not-be-recorded", evidence)
            raise ValueError("injected update failure")

        azure.side_effect = respond
        with self.assertRaisesRegex(ValueError, "injected update"):
            deploy(request, self.env, self.output)
        receipt = json.loads(self.output.read_text())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("image_update", receipt["phase"])
        self.assertEqual("old-image", receipt["previous_image"])
        self.assertNotIn("revision", receipt)

    @patch("deployment.az")
    @patch("deployment.write_evidence")
    def test_cannot_write_previous_evidence_stops_before_update(self, write, azure):
        request = validate(self.candidate, self.run, self.env)
        azure.side_effect = [{"id": self.uuid, "tenantId": self.uuid}, self.app("old")]
        write.side_effect = [None, OSError("disk unavailable"), None]
        with self.assertRaises(OSError):
            deploy(request, self.env, self.output)
        self.assertFalse(any("update" in call.args for call in azure.call_args_list))

    @patch("deployment.az")
    def test_cli_rejects_failed_ci_without_calling_azure(self, azure):
        candidate = self.output.with_name("candidate.json")
        run = self.output.with_name("candidate-run.json")
        candidate.write_text(json.dumps(self.candidate))
        run.write_text(json.dumps({**self.run, "conclusion": "failure"}))
        with patch.dict("os.environ", self.env, clear=True), patch.object(sys, "argv", ["deployment.py", "deploy",
                "--candidate", str(candidate), "--run", str(run), "--output", str(self.output)]):
            with self.assertRaises(SystemExit) as failure:
                main()
        self.assertEqual(1, failure.exception.code)
        azure.assert_not_called()
        self.assertEqual("rejected", json.loads(self.output.read_text())["status"])

    def test_cli_rejects_missing_configuration_without_invoking_azure(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            candidate, run = directory / "candidate.json", directory / "run.json"
            candidate.write_text(json.dumps(self.candidate))
            run.write_text(json.dumps(self.run))
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("deployment.py")), "deploy",
                                     "--candidate", str(candidate), "--run", str(run), "--output", str(directory / "result.json")],
                                    env={"PATH": "", "ACA_DEPLOYMENT_ENABLED": "false"}, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn("rejected", result.stderr)
            self.assertNotIn("Traceback", result.stderr)
            self.assertEqual("rejected", json.loads((directory / "result.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()

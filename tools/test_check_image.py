import subprocess
import unittest
from unittest.mock import patch

from check_image import check_image


class ImageChecks(unittest.TestCase):
    def results(self):
        return [
            subprocess.CompletedProcess([], 0, '10001:10001\n'),
            subprocess.CompletedProcess([], 0, 'source-sha\n'),
            subprocess.CompletedProcess([], 0, 'https://github.com/pipesor-org/pipesor-recommendation-service\n'),
            subprocess.CompletedProcess([], 1, 'APPLICATION FAILED TO START\nReason: pipesor.tenant-id is required', ''),
            subprocess.CompletedProcess([], 0),
        ]

    def test_checks_identity_network_isolation_expected_failure_and_cleanup(self):
        with patch('check_image.subprocess.run', side_effect=self.results()) as run:
            check_image('tested-image', 'source-sha')
        commands = run.call_args_list
        self.assertIn('--network', commands[3].args[0])
        self.assertEqual(commands[3].args[0][-3:], ['--network', 'none', 'tested-image'])
        self.assertEqual(commands[-1].args[0][:3], ['docker', 'rm', '--force'])
        self.assertTrue(all(call.kwargs['timeout'] > 0 for call in commands))

    def test_accepts_a_spring_startup_failure_before_failure_analysis(self):
        # Datasource creation fails before Spring's failure analysis when PIPESOR_DATABASE_URL is missing.
        results = self.results()[:3] + [
            subprocess.CompletedProcess([], 1, "Application run failed\n'url' must start with \"jdbc\"", ''),
            subprocess.CompletedProcess([], 0)]
        with patch('check_image.subprocess.run', side_effect=results):
            check_image('tested-image', 'source-sha')

    def test_rejects_wrong_identity_and_always_cleans_up(self):
        for index in range(3):
            with self.subTest(index=index):
                results = self.results()[:index] + [subprocess.CompletedProcess([], 0, 'wrong')]
                results.append(subprocess.CompletedProcess([], 0))
                with patch('check_image.subprocess.run', side_effect=results) as run:
                    with self.assertRaisesRegex(RuntimeError, 'source identity'):
                        check_image('tested-image', 'source-sha')
                self.assertEqual(run.call_args_list[-1].args[0][:3], ['docker', 'rm', '--force'])

    def test_startup_success_unrelated_failure_and_timeout_are_rejected_and_cleaned(self):
        for outcome in (subprocess.CompletedProcess([], 0, 'APPLICATION FAILED TO START is required', ''),
                        subprocess.CompletedProcess([], 1, 'unrelated failure', ''),
                        subprocess.CompletedProcess([], 125, 'APPLICATION FAILED TO START is required', ''),
                        subprocess.TimeoutExpired('docker', 60)):
            with self.subTest(outcome=outcome):
                results = self.results()[:3] + [outcome, subprocess.CompletedProcess([], 0)]
                with patch('check_image.subprocess.run', side_effect=results) as run:
                    with self.assertRaises((RuntimeError, subprocess.TimeoutExpired)):
                        check_image('tested-image', 'source-sha')
                self.assertEqual(run.call_args_list[-1].args[0][:3], ['docker', 'rm', '--force'])


if __name__ == '__main__':
    unittest.main()

"""Check the packaged image locally without credentials or external networking."""

import os
import subprocess
import sys
from uuid import uuid4


def check_image(image, revision):
    name = 'recommendation-check-' + uuid4().hex[:12]
    try:
        for template, expected in (
            ('{{.Config.User}}', '10001:10001'),
            ('{{index .Config.Labels "org.opencontainers.image.revision"}}', revision),
            ('{{index .Config.Labels "org.opencontainers.image.source"}}',
             'https://github.com/pipesor-org/pipesor-recommendation-service'),
        ):
            actual = subprocess.run(['docker', 'image', 'inspect', image, '--format', template],
                                    check=True, capture_output=True, text=True, timeout=30).stdout.strip()
            if actual != expected:
                raise RuntimeError('Packaged image user or source identity does not match')
        result = subprocess.run(['docker', 'run', '--rm', '--name', name, '--network', 'none', image],
                                capture_output=True, text=True, timeout=60)
        output = result.stdout + result.stderr
        # Without platform settings Spring Boot must refuse to start (same guarantee as Pipeline's check). A clearer
        # missing-property diagnostic before datasource creation is tracked in the service's startup issue.
        startup_failed = 'APPLICATION FAILED TO START' in output or 'Application run failed' in output
        if result.returncode != 1 or not startup_failed:
            raise RuntimeError('Packaged service did not reject missing runtime configuration as expected')
        print('Image checks passed: nonroot, OCI source/revision, missing-configuration rejection')
    finally:
        subprocess.run(['docker', 'rm', '--force', name], capture_output=True, timeout=15)


if __name__ == '__main__':
    check_image(sys.argv[1], os.environ['GITHUB_SHA'])

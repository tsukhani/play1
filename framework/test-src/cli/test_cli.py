"""
Integration tests for the play CLI commands.
Uses Python unittest (stdlib only). No external dependencies.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

PLAY_SCRIPT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', 'play'))


def run_play(args, timeout=60):
    """Run a play command and return the CompletedProcess."""
    return subprocess.run(
        [sys.executable, PLAY_SCRIPT] + args,
        capture_output=True,
        text=True,
        timeout=timeout
    )


class TestPlayVersion(unittest.TestCase):

    def test_version_outputs_version_number(self):
        result = run_play(['version'])
        self.assertIn('play!', result.stdout)
        self.assertIn('1.11', result.stdout)


class TestPlayHelp(unittest.TestCase):

    def test_help_shows_usage(self):
        result = run_play(['help'])
        self.assertEqual(result.returncode, 0)
        self.assertIn('core commands', result.stdout.lower())


class TestPlayNew(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix='play-cli-test-')
        self.app_path = os.path.join(self.tmpdir, 'testapp')

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_creates_directory_structure(self):
        result = run_play(['new', self.app_path, '--name=TestApp'])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)

        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'app', 'controllers')))
        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'app', 'models')))
        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'app', 'views')))
        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'conf')))
        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'public')))

    def test_sets_application_name(self):
        run_play(['new', self.app_path, '--name=MyTestApp'])

        conf_path = os.path.join(self.app_path, 'conf', 'application.conf')
        self.assertTrue(os.path.isfile(conf_path))
        with open(conf_path) as f:
            content = f.read()
        self.assertIn('application.name=MyTestApp', content)

    def test_creates_valid_dependencies_yml(self):
        run_play(['new', self.app_path, '--name=DepsApp'])

        deps_path = os.path.join(self.app_path, 'conf', 'dependencies.yml')
        self.assertTrue(os.path.isfile(deps_path))
        with open(deps_path) as f:
            content = f.read()
        self.assertIn('play', content)


if __name__ == '__main__':
    unittest.main()

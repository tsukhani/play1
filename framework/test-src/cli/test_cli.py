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


class TestPlayNewWithNuxt(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix='play-cli-test-nuxt-')
        self.app_path = os.path.join(self.tmpdir, 'nuxtapp')

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_creates_nuxt_frontend(self):
        result = run_play(['new', self.app_path, '--name=NuxtApp', '--frontend=nuxt'])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)

        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'frontend')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'package.json')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'nuxt.config.ts')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'pages', 'index.vue')))

    def test_creates_api_controller(self):
        run_play(['new', self.app_path, '--name=NuxtApp', '--frontend=nuxt'])

        api_controller = os.path.join(self.app_path, 'app', 'controllers', 'ApiController.java')
        self.assertTrue(os.path.isfile(api_controller))
        with open(api_controller) as f:
            content = f.read()
        self.assertIn('renderJSON', content)

    def test_adds_api_route(self):
        run_play(['new', self.app_path, '--name=NuxtApp', '--frontend=nuxt'])

        routes_path = os.path.join(self.app_path, 'conf', 'routes')
        with open(routes_path) as f:
            content = f.read()
        self.assertIn('/api/status', content)
        self.assertIn('ApiController.status', content)

    def test_substitutes_app_name_in_frontend(self):
        run_play(['new', self.app_path, '--name=MyNuxtApp', '--frontend=nuxt'])

        with open(os.path.join(self.app_path, 'frontend', 'package.json')) as f:
            content = f.read()
        self.assertIn('MyNuxtApp-frontend', content)


class TestPlayDist(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix='play-cli-test-dist-')
        self.app_path = os.path.join(self.tmpdir, 'distapp')
        run_play(['new', self.app_path, '--name=DistApp'])

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_creates_zip(self):
        result = run_play(['dist', self.app_path])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)

        zip_path = os.path.join(self.app_path, 'dist', 'distapp.zip')
        self.assertTrue(os.path.isfile(zip_path))

    def test_custom_output_path(self):
        out = os.path.join(self.tmpdir, 'custom.zip')
        result = run_play(['dist', self.app_path, '-o', out])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)
        self.assertTrue(os.path.isfile(out))

    def test_includes_app_and_conf(self):
        import zipfile
        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'distapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertTrue(any('app/controllers' in n for n in names))
        self.assertTrue(any('conf/application.conf' in n for n in names))
        self.assertTrue(any('conf/routes' in n for n in names))

    def test_excludes_tmp_and_logs(self):
        import zipfile
        # Create tmp and logs dirs that should be excluded
        os.makedirs(os.path.join(self.app_path, 'tmp'))
        os.makedirs(os.path.join(self.app_path, 'logs'))
        with open(os.path.join(self.app_path, 'tmp', 'dump.txt'), 'w') as f:
            f.write('temp')
        with open(os.path.join(self.app_path, 'logs', 'app.log'), 'w') as f:
            f.write('log')

        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'distapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertFalse(any('/tmp/' in n for n in names))
        self.assertFalse(any('/logs/' in n for n in names))

    def test_includes_frontend_excludes_node_modules(self):
        import zipfile
        # Create a frontend dir with source and node_modules
        frontend = os.path.join(self.app_path, 'frontend')
        os.makedirs(os.path.join(frontend, 'components'))
        os.makedirs(os.path.join(frontend, 'node_modules', 'somepkg'))
        os.makedirs(os.path.join(frontend, '.nuxt'))
        with open(os.path.join(frontend, 'components', 'App.vue'), 'w') as f:
            f.write('<template></template>')
        with open(os.path.join(frontend, 'node_modules', 'somepkg', 'index.js'), 'w') as f:
            f.write('module.exports = {}')
        with open(os.path.join(frontend, '.nuxt', 'cache.json'), 'w') as f:
            f.write('{}')

        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'distapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertTrue(any('frontend/components/App.vue' in n for n in names))
        self.assertFalse(any('node_modules' in n for n in names))
        self.assertFalse(any('.nuxt' in n for n in names))


if __name__ == '__main__':
    unittest.main()

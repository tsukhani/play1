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
        self.assertRegex(result.stdout, r'play!\s+\d+\.\d+\.\d+')


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
        result = run_play(['new', self.app_path, '--name=NuxtApp', '--frontend'])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)

        self.assertTrue(os.path.isdir(os.path.join(self.app_path, 'frontend')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'package.json')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'nuxt.config.ts')))
        self.assertTrue(os.path.isfile(os.path.join(self.app_path, 'frontend', 'pages', 'index.vue')))

    def test_creates_api_controller(self):
        run_play(['new', self.app_path, '--name=NuxtApp', '--frontend'])

        api_controller = os.path.join(self.app_path, 'app', 'controllers', 'ApiController.java')
        self.assertTrue(os.path.isfile(api_controller))
        with open(api_controller) as f:
            content = f.read()
        self.assertIn('renderJSON', content)

    def test_adds_api_route(self):
        run_play(['new', self.app_path, '--name=NuxtApp', '--frontend'])

        routes_path = os.path.join(self.app_path, 'conf', 'routes')
        with open(routes_path) as f:
            content = f.read()
        self.assertIn('/api/status', content)
        self.assertIn('ApiController.status', content)

    def test_substitutes_app_name_in_frontend(self):
        run_play(['new', self.app_path, '--name=MyNuxtApp', '--frontend'])

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


class TestPlayDistGitIgnore(unittest.TestCase):
    """Tests that play dist honors .gitignore when inside a git repo."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix='play-cli-test-dist-git-')
        self.app_path = os.path.join(self.tmpdir, 'gitapp')
        run_play(['new', self.app_path, '--name=GitApp'])
        # Initialize a git repo so dist uses the git-aware path
        subprocess.run(['git', 'init'], cwd=self.app_path, capture_output=True)
        subprocess.run(['git', 'add', '.'], cwd=self.app_path, capture_output=True)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_excludes_gitignored_files(self):
        import zipfile
        # Create a file and then gitignore it
        with open(os.path.join(self.app_path, 'secret.env'), 'w') as f:
            f.write('PASSWORD=hunter2')
        with open(os.path.join(self.app_path, '.gitignore'), 'a') as f:
            f.write('\nsecret.env\n')

        result = run_play(['dist', self.app_path])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)
        self.assertIn('gitignore', result.stdout.lower())

        zip_path = os.path.join(self.app_path, 'dist', 'gitapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertFalse(any('secret.env' in n for n in names))

    def test_excludes_gitignored_directories(self):
        import zipfile
        # Create node_modules and gitignore it
        os.makedirs(os.path.join(self.app_path, 'node_modules', 'pkg'))
        with open(os.path.join(self.app_path, 'node_modules', 'pkg', 'index.js'), 'w') as f:
            f.write('module.exports = {}')
        with open(os.path.join(self.app_path, '.gitignore'), 'a') as f:
            f.write('\nnode_modules/\n')

        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'gitapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertFalse(any('node_modules' in n for n in names))

    def test_honors_subdirectory_gitignore(self):
        import zipfile
        # Create frontend with its own .gitignore
        frontend = os.path.join(self.app_path, 'frontend')
        os.makedirs(os.path.join(frontend, 'src'))
        os.makedirs(os.path.join(frontend, 'build-output'))
        with open(os.path.join(frontend, 'src', 'App.vue'), 'w') as f:
            f.write('<template></template>')
        with open(os.path.join(frontend, 'build-output', 'bundle.js'), 'w') as f:
            f.write('console.log("built")')
        with open(os.path.join(frontend, '.gitignore'), 'w') as f:
            f.write('build-output/\n')
        # Stage the non-ignored files so git ls-files picks them up
        subprocess.run(['git', 'add', 'frontend/src', 'frontend/.gitignore'],
                       cwd=self.app_path, capture_output=True)

        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'gitapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertTrue(any('frontend/src/App.vue' in n for n in names))
        self.assertFalse(any('build-output' in n for n in names))

    def test_includes_tracked_files(self):
        import zipfile
        run_play(['dist', self.app_path])

        zip_path = os.path.join(self.app_path, 'dist', 'gitapp.zip')
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
        self.assertTrue(any('conf/application.conf' in n for n in names))
        self.assertTrue(any('conf/routes' in n for n in names))


class TestPlaySecret(unittest.TestCase):
    """PF-71: secrets live at certs/.env, not project root .env."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix='play-cli-test-secret-')
        self.app_path = os.path.join(self.tmpdir, 'secretapp')
        run_play(['new', self.app_path, '--name=SecretApp'])

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_play_new_writes_secret_to_certs_dotenv(self):
        # After `play new`, the secret file lives at certs/.env. Project-root
        # .env should never exist (PF-71 alpha — no legacy back-compat).
        certs_env = os.path.join(self.app_path, 'certs', '.env')
        legacy_env = os.path.join(self.app_path, '.env')
        self.assertTrue(os.path.isfile(certs_env), '%s should exist' % certs_env)
        self.assertFalse(os.path.exists(legacy_env), '%s should NOT exist (PF-71)' % legacy_env)
        with open(certs_env) as f:
            content = f.read()
        self.assertIn('PLAY_SECRET=', content)

    def test_dotenv_example_lives_under_certs(self):
        # PF-71: .env.example moved to certs/ to keep template + populated file
        # adjacent. The legacy project-root .env.example must not exist.
        example_certs = os.path.join(self.app_path, 'certs', '.env.example')
        example_legacy = os.path.join(self.app_path, '.env.example')
        self.assertTrue(os.path.isfile(example_certs),
                        '%s should exist' % example_certs)
        self.assertFalse(os.path.exists(example_legacy),
                         '%s should NOT exist (PF-71)' % example_legacy)

    @unittest.skipIf(os.name == 'nt',
                     'POSIX file modes do not apply on Windows; chmod silently no-ops')
    def test_secret_file_mode_is_600(self):
        # Production-grade hardening: the secret file must not be world-/group-
        # readable even with a permissive umask.
        certs_env = os.path.join(self.app_path, 'certs', '.env')
        mode = os.stat(certs_env).st_mode & 0o777
        self.assertEqual(mode, 0o600,
                         'expected mode 0o600, got 0o%o' % mode)

    def test_gitignore_protects_certs_dotenv_but_tracks_template(self):
        # PF-71: every fresh app must have a .gitignore that ignores the
        # populated certs/.env (which holds the real secret) but tracks
        # certs/.env.example (the template). Without this, operators could
        # accidentally commit their PLAY_SECRET via `git add .`.
        gitignore = os.path.join(self.app_path, '.gitignore')
        self.assertTrue(os.path.isfile(gitignore),
                        '.gitignore must be in every fresh app')
        # Initialize a real git repo so check-ignore can verify the patterns.
        subprocess.run(['git', 'init', '-q'], cwd=self.app_path,
                       capture_output=True, check=True)
        env_check = subprocess.run(
            ['git', 'check-ignore', '-q', 'certs/.env'],
            cwd=self.app_path, capture_output=True)
        self.assertEqual(env_check.returncode, 0,
                         'certs/.env should be gitignored')
        example_check = subprocess.run(
            ['git', 'check-ignore', '-q', 'certs/.env.example'],
            cwd=self.app_path, capture_output=True)
        self.assertEqual(example_check.returncode, 1,
                         'certs/.env.example should NOT be gitignored '
                         '(it is the committed template)')

    def test_play_secret_rerun_updates_in_place(self):
        # Re-running `play secret` rewrites the same certs/.env file with a
        # fresh value, preserving any unrelated lines the operator added.
        certs_env = os.path.join(self.app_path, 'certs', '.env')
        with open(certs_env) as f:
            original = f.read()

        # Simulate an operator-added unrelated env var.
        with open(certs_env, 'a') as f:
            f.write('CUSTOM_VAR=abc123\n')

        result = run_play(['secret', self.app_path])
        self.assertEqual(result.returncode, 0, msg=result.stderr + result.stdout)

        with open(certs_env) as f:
            after = f.read()
        # PLAY_SECRET line should have changed (new value).
        self.assertNotEqual(original, after)
        self.assertIn('PLAY_SECRET=', after)
        # The unrelated CUSTOM_VAR must still be there — writeEnvVar preserves
        # other lines.
        self.assertIn('CUSTOM_VAR=abc123', after)


class TestAutotestUrl(unittest.TestCase):
    """PF-73: FirePhoque always connects over plain HTTP, regardless of any
    https.port / %test.https.port setting. The pre-PF-73 code's truthy check
    on https.port made the literal string "-1" sentinel (written by PF-72)
    select itself as the port, producing the unreachable URL
    https://localhost:-1/@tests."""

    @classmethod
    def setUpClass(cls):
        # Make framework/pym importable so we can call _firephoqueHttpPort
        # directly. Mirrors the sys.path setup in the top-level `play` script.
        pym = os.path.abspath(os.path.join(
            os.path.dirname(__file__), '..', '..', 'pym'))
        if pym not in sys.path:
            sys.path.insert(0, pym)
        from play.commands.autotest import _firephoqueHttpPort
        cls.firephoqueHttpPort = staticmethod(_firephoqueHttpPort)

    def _fakeApp(self, conf):
        class FakeApp:
            def __init__(self, c): self._c = c
            def readConf(self, key): return self._c.get(key, '')
        return FakeApp(conf)

    def test_https_9443_set_uses_http_port_only(self):
        # Even if dev https.port is 9443, FirePhoque must use http.port.
        app = self._fakeApp({'http.port': '9000', 'https.port': '9443'})
        self.assertEqual(self.firephoqueHttpPort(app), '9000')

    def test_test_https_minus_one_does_not_become_the_port(self):
        # The PF-72 default. Pre-PF-73 this would have been picked up because
        # the truthy check on the string "-1" passes, producing :-1 in the URL.
        app = self._fakeApp({'http.port': '9000', 'https.port': '-1'})
        self.assertEqual(self.firephoqueHttpPort(app), '9000')

    def test_custom_test_https_19443_ignored(self):
        # Integration-testapp pattern (%test.https.port=19443). Irrelevant to
        # autotest's URL — autotest still uses http.port.
        app = self._fakeApp({'http.port': '9000', 'https.port': '19443'})
        self.assertEqual(self.firephoqueHttpPort(app), '9000')

    def test_custom_http_port_honored(self):
        app = self._fakeApp({'http.port': '8080', 'https.port': '9443'})
        self.assertEqual(self.firephoqueHttpPort(app), '8080')

    def test_no_http_port_falls_back_to_9000(self):
        # Framework default per Server.java when neither http.port nor https.port
        # is set is 9000; autotest mirrors that fallback.
        app = self._fakeApp({})
        self.assertEqual(self.firephoqueHttpPort(app), '9000')


if __name__ == '__main__':
    unittest.main()

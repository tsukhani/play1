"""Tests of run.py's parsing, which no measurement checks: python3 -m unittest discover -s benchmarks/helidon"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run  # noqa: E402


def harness(**attrs):
    """A Harness without its constructor, which runs java and git."""
    h = object.__new__(run.Harness)
    h.client_prefix, h.guard = [], None
    h.log = lambda msg: None
    h.__dict__.update(attrs)
    return h


def output(text):
    return mock.Mock(stdout=text)


# what oha --debug prints: the request it sends and, when one came, the response
REQUEST = "Request {\n    method: GET,\n    uri: /plaintext,\n    version: HTTP/2.0,\n}\n"
RESPONSE = "Response {\n    status: 200,\n    version: %s,\n}\n"


class ProveTest(unittest.TestCase):
    def prove(self, curl, oha):
        with mock.patch.object(run.subprocess, "run", side_effect=[output(curl), output(oha)]):
            return harness().prove(run.Cell("h2-tls", 16), "https://127.0.0.1:1/plaintext")

    def test_an_h2_response_proves_the_cell(self):
        self.assertEqual(self.prove("2 200", REQUEST + RESPONSE % "HTTP/2.0"), {"curl": "2 200", "oha": "HTTP/2.0"})

    def test_oha_without_a_response_proves_nothing(self):
        # a refused connect or a TLS server that offers no h2 prints only the request, at the version oha asked for
        with self.assertRaises(RuntimeError):
            self.prove("2 200", REQUEST)

    def test_an_http11_response_fails_the_cell(self):
        with self.assertRaises(RuntimeError):
            self.prove("2 200", REQUEST + RESPONSE % "HTTP/1.1")


H2LOAD = """starting benchmark...
TLS Protocol: TLSv1.3
Cipher: TLS_AES_128_GCM_SHA256
Application protocol: h2
finished in 20.00s, 230000.00 req/s, 10.00MB/s
requests: 4600000 total, 4600000 started, 4600000 done, 4600000 succeeded, 0 failed, 0 errored, 0 timeout
                 min         max         median     p95        p99        mean         sd        +/- sd
request     :      150us     40.19ms     3.95ms    11.96ms    14.22ms     4.34ms      2.1ms    80.57%
"""


class H2loadTest(unittest.TestCase):
    def h2load(self, client_cpu):
        """One h2load run of 22 s at 3 threads that used client_cpu seconds."""
        h = harness(h2load_threads=3, measured=20, h2load_warm=2)
        with mock.patch.object(run.subprocess, "run", return_value=output(H2LOAD)), \
                mock.patch.object(run, "children_cpu", side_effect=[0.0, client_cpu]), \
                mock.patch.object(run, "cpu_seconds", side_effect=[0.0, 250.0]), \
                mock.patch.object(run.time, "monotonic", side_effect=[0.0, 22.0]):
            return h.h2load("mp", 1, "https://127.0.0.1:1/plaintext", 64, run.Cell("h2-tls", 16), "plaintext")

    def test_reads_the_rate_and_the_percentiles(self):
        r = self.h2load(33.0)
        self.assertEqual((r["rps"], r["p50_ms"], r["p99_ms"], r["protocol"]), (230000.0, 3.95, 14.22, "h2"))
        self.assertEqual((r["cpu_per_thread"], r["server_cores"]), (0.5, 11.36))

    def test_the_summary_says_when_h2loads_threads_were_busy(self):
        # at 0.9 of a core per thread or more h2load may be the limit: on Linux it held Helidon SE 13% low at 3 threads
        busy = dict(ok_rps=200000, rps=200000, h2load=self.h2load(66.0))
        idle = dict(ok_rps=200000, rps=200000, h2load=self.h2load(33.0))
        self.assertIn("h2load threads at 1.00 cores each", run.h2load_cell(busy))
        self.assertNotIn("cores each", run.h2load_cell(idle))


class QuarkusCommandTest(unittest.TestCase):
    def harness(self, threads="worker", tls="boringssl"):
        h = harness(out="/out", server_prefix=[], quarkus_threads=threads, quarkus_tls=tls)
        h.certificate = lambda: ("/tls/cert.pem", "/tls/key.pem")
        return h

    def test_tls_opens_the_https_listener_alone_on_the_chosen_engine(self):
        cwd, line = self.harness().command("quarkus", 8443, tls=True)
        self.assertEqual(cwd, "/out/quarkus-worker")
        self.assertEqual(line, ["java", "-Dquarkus.profile=tls", "-Dquarkus.http.ssl-port=8443",
                                "-Dquarkus.tls.key-store.pem.0.cert=/tls/cert.pem",
                                "-Dquarkus.tls.key-store.pem.0.key=/tls/key.pem", "-Dquarkus.tls.ssl-engine=openssl",
                                "-jar", "quarkus-run.jar"])
        self.assertIn("-Dquarkus.tls.ssl-engine=jdkssl", self.harness(tls="jdk").command("quarkus", 1, tls=True)[1])

    def test_the_training_run_records_the_cache_and_ends_on_the_harness_signal(self):
        cwd, line = self.harness("virtual").command("quarkus", 9000, "train")
        self.assertEqual(cwd, "/out/quarkus-aot-virtual")
        self.assertEqual(line, ["java", "-XX:AOTCacheOutput=app.aot", "-Dquarkus.http.port=9000", "-jar",
                                "quarkus-run.jar"])
        self.assertEqual(self.harness().command("quarkus", 9000, "use")[1][1], "-XX:AOTCache=app.aot")

    def test_only_the_virtual_build_sets_the_execution_model(self):
        self.assertEqual(self.harness().quarkus_flags(), [])
        self.assertEqual(self.harness().quarkus_flags("virtual"), [run.QUARKUS_VIRTUAL])

    def test_an_engine_other_than_the_one_asked_for_stops_the_run(self):
        probe = {"version": "TLSv1.3", "cipher": "TLS_AES_128_GCM_SHA256", "alpn": "h2"}
        for tls, loaded in (("boringssl", False), ("jdk", True)):
            with mock.patch.object(run, "tls_probe", return_value=probe), \
                    mock.patch.object(run, "listener", return_value=1), \
                    mock.patch.object(run, "tcnative_loaded", return_value=loaded):
                with self.assertRaises(RuntimeError):
                    self.harness(tls=tls).tls_info("quarkus", 8443)
        with mock.patch.object(run, "tls_probe", return_value=probe), mock.patch.object(run, "listener", return_value=1), \
                mock.patch.object(run, "tcnative_loaded", return_value=True):
            self.assertEqual(self.harness().tls_info("quarkus", 8443)["provider"], run.QUARKUS_TLS["boringssl"][1])


if __name__ == "__main__":
    unittest.main()

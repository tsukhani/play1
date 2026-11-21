#!/usr/bin/env python3
"""The fork against Helidon 4.5.7 SE and MP, one application at a time, on one JDK and one machine (PF-235, PF-236).

    python3 run.py --framework <a built checkout of this repository> [--out <dir>] [--steps ...] [--apps ...] [--quick]

Steps, all of them unless --steps names some:
  build    copies fork, helidon-se and helidon-mp into the output directory, gives each the checkout's Gradle wrapper,
           builds the fork's bundle (playBundle) and the Helidon applications (installDist)
  startup  launch to the first 200 on /plaintext, the median of 5, and the server JVM's RSS right after
  load     per endpoint, warm-up and then a measured run with oha (64 connections, 16 for /sse): requests per second,
           p50 and p99, success rate, and the server JVM's peak RSS during the measured run
  aot      each application's own AOT training (the bundle's aot-gen; java -XX:AOTCacheOutput with -Dexit.on.started=!
           for Helidon), then startup again from the cache
  dev      from saving an edit to the first response that carries it: the fork in DEV mode under ./gradlew playRun,
           Helidon under Gradle as an incremental compile and a new JVM, since Helidon reloads nothing in a running JVM
           and its own dev loop supports Maven only

The checkout must be built: framework/play-<version>.jar (ant jar) and framework/gradle-plugin-repo (./gradlew
:gradle-plugin:publishAllPublicationsToFrameworkLocalRepository). Needs JDK 25 as java on PATH or in JAVA_HOME, oha on
PATH, and git and unzip. Numbers are comparisons on one machine in one run, never absolute figures.
"""
import argparse
import datetime
import http.client
import json
import os
import platform
import secrets
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HELIDON = "4.5.7"
APPS = ("fork", "se", "mp")
ENDPOINTS = {
    "fork": ["plaintext", "json", "db", "jpa", "sse"],
    "se": ["plaintext", "json", "db", "sse"],
    "mp": ["plaintext", "json", "db", "jpa", "sse"],
}
LABELS = {"fork": "Fork", "se": "Helidon SE", "mp": "Helidon MP"}


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def get(port, path="/plaintext", timeout=2.0):
    try:
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        c.request("GET", path)
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, body.decode("utf-8", "replace")
    except OSError:
        return None, None


def listener(port):
    """The pid that listens on the port: lsof, or ss where there is no lsof."""
    if shutil.which("lsof"):
        out = subprocess.run(["lsof", "-tiTCP:%d" % port, "-sTCP:LISTEN"], capture_output=True, text=True).stdout.split()
        return int(out[0]) if out else None
    out = subprocess.run(["ss", "-ltnpH", "sport = :%d" % port], capture_output=True, text=True).stdout
    marker = "pid="
    return int(out.split(marker, 1)[1].split(",", 1)[0]) if marker in out else None


def rss_mb(pid):
    out = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    return int(out) / 1024 if out else None


def calibration():
    """One core, fixed work: a slower figure than usual means a throttled or busy machine."""
    t = time.perf_counter()
    s = 0
    for i in range(25_000_000):
        s += i * i
    return round(time.perf_counter() - t, 2)


class Harness:
    def __init__(self, args):
        self.framework = os.path.abspath(args.framework)
        self.out = os.path.abspath(args.out)
        self.results_dir = os.path.join(self.out, "results")
        os.makedirs(self.results_dir, exist_ok=True)
        self.quick = args.quick
        self.warm, self.measured = (2, 3) if args.quick else (10, 20)
        self.starts = 2 if args.quick else 5
        self.edit_warm, self.edits = (1, 2) if args.quick else (3, 10)
        self.apps = args.apps
        self.env = dict(os.environ, PLAY_SECRET=secrets.token_hex(32))
        self.stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.results = {"started": self.stamp, "quick": self.quick, "environment": self.environment(),
                        "startup": {}, "load": {}, "dev": {}}

    # -- environment ---------------------------------------------------------------------------------------------

    def environment(self):
        java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
        oha = subprocess.run(["oha", "--version"], capture_output=True, text=True).stdout.strip() if shutil.which("oha") else None
        commit = subprocess.run(["git", "-C", self.framework, "log", "-1", "--format=%h %s"], capture_output=True,
                                text=True).stdout.strip()
        version = open(os.path.join(self.framework, "framework/src/play/version")).read().strip()
        memory = None
        if sys.platform == "darwin":
            memory = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout) // 2**30
        elif os.path.exists("/proc/meminfo"):
            memory = int(open("/proc/meminfo").readline().split()[1]) // 2**20
        return {"java": java[0] if java else None, "os": "%s %s" % (platform.system(), platform.release()),
                "arch": platform.machine(), "cores": os.cpu_count(), "memory_gb": memory, "oha": oha,
                "framework": "%s (%s)" % (version, commit), "helidon": HELIDON}

    def log(self, msg):
        line = time.strftime("%H:%M:%S ") + msg
        print(line, flush=True)
        with open(os.path.join(self.results_dir, "run-%s.log" % self.stamp), "a") as f:
            f.write(line + "\n")

    def settle(self):
        """At most two minutes for the 1-minute load average to drop under 3, then the calibration."""
        for _ in range(24):
            if os.getloadavg()[0] < 3:
                break
            time.sleep(5)
        return {"load": round(os.getloadavg()[0], 2), "calibration_s": calibration()}

    # -- build ---------------------------------------------------------------------------------------------------

    def copy_app(self, name):
        target = os.path.join(self.out, name)
        if os.path.exists(target):
            shutil.rmtree(target)
        shutil.copytree(os.path.join(HERE, name), target)
        shutil.copy2(os.path.join(self.framework, "gradlew"), target)
        shutil.copytree(os.path.join(self.framework, "gradle", "wrapper"), os.path.join(target, "gradle", "wrapper"))
        return target

    def gradle(self, cwd, *tasks):
        self.log("gradle %s in %s" % (" ".join(tasks), os.path.basename(cwd)))
        subprocess.run(["./gradlew", "-q", "--console=plain", *tasks], cwd=cwd, env=self.env, check=True)

    def build(self):
        fork = self.copy_app("fork")
        with open(os.path.join(fork, "gradle.properties"), "w") as f:
            f.write("benchFramework=%s\n" % self.framework)
        # playBundle ships what git ls-files lists
        subprocess.run(["git", "init", "-q"], cwd=fork, check=True)
        subprocess.run(["git", "add", "-A"], cwd=fork, check=True)
        self.gradle(fork, "playBundle")
        bundle_dir = os.path.join(self.out, "fork-bundle")
        if os.path.exists(bundle_dir):
            shutil.rmtree(bundle_dir)
        os.makedirs(bundle_dir)
        archive = [n for n in os.listdir(os.path.join(fork, "dist")) if n.endswith("-bundle.zip")][0]
        subprocess.run(["unzip", "-q", os.path.join(fork, "dist", archive)], cwd=bundle_dir, check=True)
        for name in ("helidon-se", "helidon-mp"):
            self.gradle(self.copy_app(name), "installDist")

    # -- running the applications -------------------------------------------------------------------------------

    def command(self, app, port, aot=None):
        """(working directory, command line) of a production start; aot is "train" or "use" for Helidon."""
        if app == "fork":
            root = os.path.join(self.out, "fork-bundle")
            cwd = os.path.join(root, os.listdir(root)[0])
            return cwd, ["./play", "run", "--%prod", "--http.port=%d" % port]
        name = {"se": "helidon-se", "mp": "helidon-mp"}[app]
        cwd = os.path.join(self.out, name, "build", "install", "bench-" + name)
        flag = ("-Dport=%d" if app == "se" else "-Dserver.port=%d") % port
        main = "bench.Main" if app == "se" else "io.helidon.microprofile.cdi.Main"
        cache = {None: [], "train": ["-XX:AOTCacheOutput=app.aot", "-Dexit.on.started=!"],
                 "use": ["-XX:AOTCache=app.aot"]}[aot]
        return cwd, ["java", *cache, flag, "-cp", "lib/*", main]

    def start(self, app, port, cmd=None, label=None):
        cwd, line = cmd or self.command(app, port)
        f = open(os.path.join(self.results_dir, "%s-%s.out" % (label or app, self.stamp)), "a")
        t0 = time.perf_counter()
        p = subprocess.Popen(line, cwd=cwd, env=self.env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
        while time.perf_counter() < t0 + 180:
            if get(port, timeout=1.0)[0] == 200:
                return p, (time.perf_counter() - t0) * 1000
            if p.poll() is not None:
                raise RuntimeError("%s exited with %s before it answered" % (app, p.returncode))
            time.sleep(0.005)
        raise RuntimeError("%s did not answer within 180 s" % app)

    def stop(self, p, port):
        pid = listener(port)
        if pid:
            os.kill(pid, signal.SIGTERM)
        for _ in range(400):
            if listener(port) is None and (p.poll() is not None or pid is None):
                break
            time.sleep(0.1)
        if p.poll() is None:
            try:
                os.killpg(p.pid, signal.SIGTERM)
                p.wait(30)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                os.killpg(p.pid, signal.SIGKILL)
        time.sleep(1)

    # -- measurements --------------------------------------------------------------------------------------------

    def startup(self, app, label=None, cmd_for=None):
        label = label or app
        conditions = self.settle()
        times, rss = [], []
        for _ in range(self.starts):
            port = free_port()
            p, ms = self.start(app, port, cmd_for(port) if cmd_for else None, label)
            time.sleep(0.5)
            rss.append(rss_mb(listener(port)))
            times.append(ms)
            self.stop(p, port)
        result = dict(ms=times, median_ms=statistics.median(times), rss_mb=rss, median_rss_mb=statistics.median(rss),
                      **conditions)
        self.results["startup"][label] = result
        self.log("startup %-8s median %6.0f ms (%s), RSS after start %4.0f MB" % (
            label, result["median_ms"], " ".join("%.0f" % t for t in times), result["median_rss_mb"]))

    def oha(self, port, path, seconds, conns):
        out = subprocess.run(["oha", "-z", "%ds" % seconds, "-c", str(conns), "--no-tui", "--output-format", "json",
                              "http://127.0.0.1:%d/%s" % (port, path)], capture_output=True, text=True)
        return json.loads(out.stdout)

    def load(self, app):
        conditions = self.settle()
        port = free_port()
        p, _ = self.start(app, port, label=app + "-load")
        pid = listener(port)
        time.sleep(2)
        idle = rss_mb(pid)
        rows = []
        for ep in ENDPOINTS[app]:
            conns = 16 if ep == "sse" else 64
            self.oha(port, ep, self.warm, conns)
            peak, done = [0.0], threading.Event()

            def sample():
                while not done.is_set():
                    peak[0] = max(peak[0], rss_mb(pid) or 0)
                    time.sleep(0.2)

            sampler = threading.Thread(target=sample)
            sampler.start()
            d = self.oha(port, ep, self.measured, conns)
            done.set()
            sampler.join()
            lat = d["latencyPercentiles"]
            row = dict(endpoint=ep, conns=conns, rps=d["summary"]["requestsPerSec"],
                       p50_ms=lat["p50"] * 1000 if lat["p50"] else None, p99_ms=lat["p99"] * 1000 if lat["p99"] else None,
                       success=d["summary"]["successRate"], status=d.get("statusCodeDistribution"), peak_rss_mb=peak[0])
            rows.append(row)
            self.log("load %-4s %-9s %8.0f req/s  p50 %s  p99 %s  ok %.4f  peak RSS %4.0f MB" % (
                app, ep, row["rps"], fmt_ms(row["p50_ms"]), fmt_ms(row["p99_ms"]), row["success"], row["peak_rss_mb"]))
        self.stop(p, port)
        self.results["load"][app] = dict(idle_rss_mb=idle, rows=rows, **conditions)

    def aot(self, app):
        if app == "fork":
            cwd, _ = self.command("fork", 0)
            port = free_port()
            subprocess.run(["./play", "aot-gen", "--http.port=%d" % port], cwd=cwd, env=self.env, check=True,
                           stdout=open(os.path.join(self.results_dir, "fork-aotgen-%s.out" % self.stamp), "w"),
                           stderr=subprocess.STDOUT)
            self.startup("fork", "fork-aot")
            os.remove(os.path.join(cwd, "app.aot"))
            return
        port = free_port()
        cwd, train = self.command(app, port, "train")
        subprocess.run(train, cwd=cwd, env=self.env, stdout=open(os.path.join(
            self.results_dir, "%s-train-%s.out" % (app, self.stamp)), "w"), stderr=subprocess.STDOUT)
        self.startup(app, app + "-aot", cmd_for=lambda port: self.command(app, port, "use"))
        os.remove(os.path.join(cwd, "app.aot"))

    def edit_loop(self, label, port, source, text_from, sig_marker, sig_line, start_proc, after_edit, stop_proc):
        original = open(source).read()
        p, first_ms = start_proc()
        rows = {"body": [], "sig": []}
        try:
            for kind in ("body", "sig"):
                for i in range(self.edit_warm + self.edits):
                    text = "Hello %s %d" % (kind, i)
                    content = original.replace(text_from, text_from.replace("Hello, World!", text))
                    if kind == "sig":
                        content = content.replace(sig_marker, "".join(sig_line % (k, k) for k in range(i + 1)) + sig_marker)
                    time.sleep(1.5)
                    with open(source + ".tmp", "w") as f:
                        f.write(content)
                    t0 = time.perf_counter()
                    os.replace(source + ".tmp", source)
                    p = after_edit(p)
                    while time.perf_counter() < t0 + 120:
                        status, body = get(port, timeout=5.0)
                        if status == 200 and body == text:
                            break
                        time.sleep(0.005)
                    else:
                        raise RuntimeError("%s: edit %s %d never reached the application" % (label, kind, i))
                    if i >= self.edit_warm:
                        rows[kind].append((time.perf_counter() - t0) * 1000)
        finally:
            with open(source, "w") as f:
                f.write(original)
            stop_proc(p)
        self.results["dev"][label] = dict(first_request_ms=first_ms, body_ms=rows["body"], sig_ms=rows["sig"],
                                          median_body_ms=statistics.median(rows["body"]),
                                          median_sig_ms=statistics.median(rows["sig"]))
        self.log("dev %-4s body edit median %6.0f ms, new method median %6.0f ms, first request %.0f ms" % (
            label, statistics.median(rows["body"]), statistics.median(rows["sig"]), first_ms))

    def dev(self, app):
        self.settle()
        port = free_port()
        if app == "fork":
            cwd = os.path.join(self.out, "fork")

            def start_proc():
                f = open(os.path.join(self.results_dir, "fork-dev-%s.out" % self.stamp), "a")
                p = subprocess.Popen(["./gradlew", "playRun", "-PhttpPort=%d" % port, "--console=plain"], cwd=cwd,
                                     env=self.env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
                while listener(port) is None:
                    if p.poll() is not None:
                        raise RuntimeError("playRun exited")
                    time.sleep(0.05)
                t0 = time.perf_counter()
                get(port, timeout=180)  # DEV starts the application on the first request
                return p, (time.perf_counter() - t0) * 1000

            self.edit_loop("fork", port, os.path.join(cwd, "app/controllers/Bench.java"), 'renderText("Hello, World!")',
                           "    public static void plaintext() {",
                           "    public static void extra%d() { renderText(\"%d\"); }\n", start_proc, lambda p: p,
                           lambda p: self.stop(p, port))
            return
        name = {"se": "helidon-se", "mp": "helidon-mp"}[app]
        cwd = os.path.join(self.out, name)
        gradle = ["./gradlew", "-q", "--offline", "--console=plain"]
        subprocess.run(gradle + ["classes"], cwd=cwd, env=self.env, check=True)
        cp = subprocess.run(gradle + ["printRuntimeClasspath"], cwd=cwd, env=self.env, check=True, capture_output=True,
                            text=True).stdout.strip().splitlines()[-1]
        flag = ("-Dport=%d" if app == "se" else "-Dserver.port=%d") % port
        line = ["java", flag, "-cp", cp, "bench.Main" if app == "se" else "io.helidon.microprofile.cdi.Main"]

        def launch():
            f = open(os.path.join(self.results_dir, "%s-dev-%s.out" % (app, self.stamp)), "a")
            return subprocess.Popen(line, cwd=cwd, env=self.env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)

        def kill(p):
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(30)
                except subprocess.TimeoutExpired:
                    p.kill()
            while listener(port) is not None:
                time.sleep(0.01)

        def start_proc():
            t0 = time.perf_counter()
            p = launch()
            while get(port, timeout=1.0)[0] != 200:
                if time.perf_counter() > t0 + 120:
                    kill(p)
                    raise RuntimeError("%s did not answer within 120 s" % app)
                time.sleep(0.005)
            return p, (time.perf_counter() - t0) * 1000

        def after_edit(p):
            # what a Gradle build of a framework without reload in the running JVM does on every edit
            subprocess.run(gradle + ["classes"], cwd=cwd, env=self.env, check=True)
            kill(p)
            return launch()

        source = os.path.join(cwd, "src/main/java/bench", "Main.java" if app == "se" else "BenchResource.java")
        marker = "    public static void main(String[] args)" if app == "se" else '    @GET\n    @Path("plaintext")'
        self.edit_loop(app, port, source, 'TEXT = "Hello, World!"', marker, "    static String extra%d() { return \"%d\"; }\n",
                       start_proc, after_edit, kill)

    # -- report --------------------------------------------------------------------------------------------------

    def report(self):
        path = os.path.join(self.results_dir, "run-%s.json" % self.stamp)
        with open(path, "w") as f:
            json.dump(self.results, f, indent=1)
        rows = summary_rows(self.results)
        header = ["Metric"] + [LABELS[a] for a in APPS]
        with open(os.path.join(self.results_dir, "summary-%s.md" % self.stamp), "w") as f:
            f.write(describe(self.results) + "\n\n")
            f.write("| " + " | ".join(header) + " |\n|" + "---|" * len(header) + "\n")
            for row in rows:
                f.write("| " + " | ".join(row) + " |\n")
        with open(os.path.join(self.results_dir, "summary-%s.jira" % self.stamp), "w") as f:
            f.write(describe(self.results) + "\n\n")
            f.write("|| " + " || ".join(header) + " ||\n")
            for row in rows:
                f.write("| " + " | ".join(row) + " |\n")
        self.log("results: %s" % path)


def fmt_ms(value):
    return "-" if value is None else "%.2f ms" % value


def describe(results):
    e = results["environment"]
    return "%s; %s %s, %s cores, %s GB; %s; framework %s; Helidon %s; run %s%s" % (
        e["java"], e["os"], e["arch"], e["cores"], e["memory_gb"], e["oha"], e["framework"], e["helidon"],
        results["started"], " (quick: not for the record)" if results["quick"] else "")


def summary_rows(results):
    rows = []
    load = results["load"]
    for ep in ("plaintext", "json", "db", "jpa", "sse"):
        if any(ep in [r["endpoint"] for r in load.get(a, {}).get("rows", [])] for a in APPS):
            cells = []
            for a in APPS:
                row = next((r for r in load.get(a, {}).get("rows", []) if r["endpoint"] == ep), None)
                cells.append("%.0f req/s, p99 %s" % (row["rps"], fmt_ms(row["p99_ms"])) if row else "-")
            rows.append([ep] + cells)
    for label, key in (("startup, no AOT cache", ""), ("startup, AOT cache", "-aot")):
        cells = ["%.0f ms" % results["startup"][a + key]["median_ms"] if a + key in results["startup"] else "-" for a in APPS]
        if any(c != "-" for c in cells):
            rows.append([label] + cells)
    cells = ["%.0f MB" % load[a]["idle_rss_mb"] if a in load else "-" for a in APPS]
    if any(c != "-" for c in cells):
        rows.append(["memory idle after start"] + cells)
        rows.append(["peak memory under load"] + ["%.0f to %.0f MB" % (min(r["peak_rss_mb"] for r in load[a]["rows"]),
                                                                    max(r["peak_rss_mb"] for r in load[a]["rows"]))
                                                  if a in load else "-" for a in APPS])
    dev = results["dev"]
    for label, key in (("edit to new response, body", "median_body_ms"), ("edit to new response, new method", "median_sig_ms")):
        cells = ["%.0f ms" % dev[a][key] if a in dev else "-" for a in APPS]
        if any(c != "-" for c in cells):
            rows.append([label] + cells)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--framework", required=True, help="a built checkout of this repository")
    parser.add_argument("--out", default=os.path.join(HERE, "out"), help="where the copies and results go")
    parser.add_argument("--steps", nargs="+", default=["build", "startup", "load", "aot", "dev"],
                        choices=["build", "startup", "load", "aot", "dev"])
    parser.add_argument("--apps", nargs="+", default=list(APPS), choices=list(APPS))
    parser.add_argument("--quick", action="store_true", help="short runs that check the pipeline, not for the record")
    args = parser.parse_args()
    for tool in ("java", "oha", "git", "unzip"):
        if not shutil.which(tool):
            raise SystemExit("%s is not on PATH" % tool)
    harness = Harness(args)
    harness.log("run %s: %s" % (harness.stamp, describe(harness.results)))
    try:
        if "build" in args.steps:
            harness.build()
        for step in ("startup", "load", "aot", "dev"):
            if step in args.steps:
                for app in args.apps:
                    getattr(harness, step)(app)
    finally:
        harness.report()


if __name__ == "__main__":
    main()

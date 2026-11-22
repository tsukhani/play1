#!/usr/bin/env python3
"""The fork against Helidon 4.5.7 SE and MP, one application at a time, on one JDK and one machine (PF-235, PF-236).

    python3 run.py --framework <a built checkout of this repository> [--out <dir>] [--steps ...] [--apps ...] [--quick]
                   [--protocols ...] [--h2-in-flight ...] [--endpoints ...] [--helidon-writes ...]
                   [--server-cpus LIST --client-cpus LIST --guard-cpus LIST]

Steps, all of them unless --steps names some:
  build    copies fork, helidon-se and helidon-mp into the output directory, gives each the checkout's Gradle wrapper,
           builds the fork's bundle (playBundle) and the Helidon applications (installDist)
  startup  launch to the first 200 on /plaintext, the median of 5, and the server JVM's RSS right after
  load     per protocol cell and endpoint, warm-up and then a measured run with oha (64 connections, 16 for /sse):
           requests per second, p50 and p99, success rate, the server JVM's peak RSS and its CPU per request
  aot      each application's own AOT training (the bundle's aot-gen; java -XX:AOTCacheOutput with -Dexit.on.started=!
           for Helidon), then startup again from the cache
  dev      from saving an edit to the first response that carries it: the fork in DEV mode under ./gradlew playRun,
           Helidon under Gradle as an incremental compile and a new JVM, since Helidon reloads nothing in a running JVM
           and its own dev loop supports Maven only

Load cells (--protocols, all of them by default; PF-247):
  h1        HTTP/1.1 without TLS over kept-alive connections
  h1-close  HTTP/1.1 without TLS, a new connection per request (oha --disable-keepalive)
  h1-tls    HTTP/1.1 over TLS
  h2-tls    HTTP/2 over TLS through ALPN, at each of --h2-in-flight requests in flight per connection (1 4 16)
The TLS cells use one self-signed certificate that the harness generates under <out>/tls with openssl. Before each
TLS cell curl and oha --debug must show the protocol, and the 16-in-flight HTTP/2 cells are measured again with h2load.

The checkout must be built: framework/play-<version>.jar (ant jar) and framework/gradle-plugin-repo (./gradlew
:gradle-plugin:publishAllPublicationsToFrameworkLocalRepository). Needs JDK 25 as java on PATH or in JAVA_HOME, oha on
PATH, and git and unzip; openssl, curl and h2load for the TLS cells. Numbers are comparisons on one machine in one run,
never absolute figures.
"""
import argparse
import datetime
import http.client
import json
import os
import platform
import re
import resource
import secrets
import shutil
import signal
import socket
import ssl
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
ALL_ENDPOINTS = ("plaintext", "json", "db", "jpa", "sse")
LABELS = {"fork": "Fork", "se": "Helidon SE", "mp": "Helidon MP"}
PROTOCOLS = {
    "h1": "HTTP/1.1, kept-alive",
    "h1-close": "HTTP/1.1, new connection per request",
    "h1-tls": "HTTP/1.1 over TLS",
    "h2-tls": "HTTP/2 over TLS",
}
H2LOAD_IN_FLIGHT = 16


class Cell:
    """One protocol and, for HTTP/2, the requests each connection keeps in flight."""

    def __init__(self, protocol, in_flight=1):
        self.protocol, self.in_flight = protocol, in_flight
        self.tls = protocol.endswith("-tls")
        self.h2 = protocol == "h2-tls"
        self.close = protocol == "h1-close"

    @property
    def name(self):
        return "%s x%d" % (self.protocol, self.in_flight) if self.h2 else self.protocol

    def label(self):
        return PROTOCOLS[self.protocol] + (", %d in flight" % self.in_flight if self.h2 else "")


def cpu_list(text):
    """taskset's list syntax, 6-11,30-35, as a set of CPU numbers."""
    cpus = set()
    for part in text.split(","):
        a, _, b = part.partition("-")
        cpus.update(range(int(a), int(b or a) + 1))
    return cpus


def helidon_writes(specs):
    """--helidon-writes [app=]MODE[+nodelay], MODE direct, async:N or smart:N, as
    {app: (write-queue-length, smart-async-writes, tcp-no-delay)}. Helidon's defaults are (0, False, False)."""
    writes = {"se": (0, False, False), "mp": (0, False, False)}
    for spec in specs or []:
        app, _, mode = spec.rpartition("=")
        mode, plus, extra = mode.partition("+")
        kind, _, n = mode.partition(":")
        if plus and extra != "nodelay":
            raise SystemExit("--helidon-writes %s: the only addition is +nodelay" % spec)
        if kind == "direct" and not n:
            value = (0, False, bool(plus))
        elif kind in ("async", "smart") and n.isdigit() and int(n) > 1:
            value = (int(n), kind == "smart", bool(plus))
        else:
            raise SystemExit("--helidon-writes %s: the mode is direct, async:N or smart:N with N above 1" % spec)
        for a in ([app] if app else ["se", "mp"]):
            if a not in writes:
                raise SystemExit("--helidon-writes %s: the application is se or mp" % spec)
            writes[a] = value
    return writes


def writes_label(value):
    queue, smart, nodelay = value
    label = "direct (write-queue-length 0)" if queue <= 1 else "write-queue-length %d%s" % (
        queue, ", smart-async-writes" if smart else "")
    return label + (", tcp-no-delay" if nodelay else "")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def insecure_context(alpn=None):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    if alpn:
        ctx.set_alpn_protocols(alpn)
    return ctx


def get(port, path="/plaintext", timeout=2.0, tls=False):
    try:
        if tls:
            c = http.client.HTTPSConnection("127.0.0.1", port, timeout=timeout, context=insecure_context(["http/1.1"]))
        else:
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        c.request("GET", path)
        r = c.getresponse()
        body = r.read()
        c.close()
        return r.status, body.decode("utf-8", "replace")
    except OSError:
        return None, None


def tls_probe(port, alpn):
    """What a client offering the usual TLS 1.3 suites and this ALPN list gets: version, cipher, protocol."""
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
        with insecure_context([alpn]).wrap_socket(raw, server_hostname="localhost") as s:
            return {"version": s.version(), "cipher": s.cipher()[0], "alpn": s.selected_alpn_protocol()}


def listener(port):
    """The pid that listens on the port: lsof, or ss where there is no lsof."""
    if shutil.which("lsof"):
        out = subprocess.run(["lsof", "-tiTCP:%d" % port, "-sTCP:LISTEN"], capture_output=True, text=True).stdout.split()
        return int(out[0]) if out else None
    out = subprocess.run(["ss", "-ltnpH", "sport = :%d" % port], capture_output=True, text=True).stdout
    marker = "pid="
    return int(out.split(marker, 1)[1].split(",", 1)[0]) if marker in out else None


def owned(pid):
    """Whether this user owns the process: the harness never signals anyone else's (a shared Linux server)."""
    if os.path.isdir("/proc/%d" % pid):
        return os.stat("/proc/%d" % pid).st_uid == os.getuid()
    out = subprocess.run(["ps", "-o", "uid=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    return out == str(os.getuid())


def rss_mb(pid):
    out = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    return int(out) / 1024 if out else None


def proc_ticks(pid):
    """utime + stime of a Linux process, in clock ticks, or None once it is gone."""
    try:
        with open("/proc/%d/stat" % pid) as f:
            fields = f.read().rsplit(")", 1)[1].split()
        return int(fields[11]) + int(fields[12])
    except (OSError, IndexError, ValueError):
        return None


def cpu_seconds(pid):
    """The process's CPU time so far, user and system: /proc on Linux, ps elsewhere (hundredths of a second)."""
    if os.path.isdir("/proc/%d" % pid):
        ticks = proc_ticks(pid)
        return ticks / os.sysconf("SC_CLK_TCK") if ticks is not None else None
    out = subprocess.run(["ps", "-o", "time=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    if not out:
        return None
    days, _, clock = out.rpartition("-")
    seconds = 0.0
    for part in clock.split(":"):
        seconds = seconds * 60 + float(part)
    return seconds + (int(days) * 86400 if days else 0)


def children_cpu():
    r = resource.getrusage(resource.RUSAGE_CHILDREN)
    return r.ru_utime + r.ru_stime


def h2load_ms(text):
    """h2load's 35us, 1.01ms or 1.2s, in milliseconds."""
    m = re.fullmatch(r"([\d.]+)(us|ms|s)", text or "")
    return float(m[1]) * {"us": 0.001, "ms": 1.0, "s": 1000.0}[m[2]] if m else None


def calibration():
    """One core, fixed work: a slower figure than usual means a throttled or busy machine."""
    t = time.perf_counter()
    s = 0
    for i in range(25_000_000):
        s += i * i
    return round(time.perf_counter() - t, 2)


class Guard:
    """What other users' programs used of the benchmark CPUs while a measurement ran (Linux, --guard-cpus).

    The busy time of those CPUs in /proc/stat, less the CPU time of this user's processes over the same span: the
    live ones from /proc/<pid>/stat and the ones that ended, which the harness reaped, from getrusage. Busy includes
    irq and softirq: a kernel without CONFIG_IRQ_TIME_ACCOUNTING charges that time to the task it interrupted as well,
    and without it the benchmark's own loopback traffic came out as negative cores of other programs. Interrupt time
    is also kept apart, as irq_cores."""

    def __init__(self, cpus):
        self.cpus = cpus
        self.hz = os.sysconf("SC_CLK_TCK")
        self.uid = os.getuid()
        self.softirqd = set()
        for name in os.listdir("/proc"):
            try:
                comm = open("/proc/%s/comm" % name).read().strip() if name.isdigit() else ""
            except OSError:
                continue
            if comm.startswith("ksoftirqd/") and int(comm.split("/")[1]) in cpus:
                self.softirqd.add(name)

    def snapshot(self):
        busy = irq = 0
        with open("/proc/stat") as f:
            for line in f:
                if line.startswith("cpu") and line[3].isdigit():
                    v = line.split()
                    if int(v[0][3:]) in self.cpus:
                        # user nice system idle iowait irq softirq steal
                        busy += int(v[1]) + int(v[2]) + int(v[3]) + int(v[6]) + int(v[7]) + int(v[8])
                        irq += int(v[6]) + int(v[7])
        own = soft = 0
        for name in os.listdir("/proc"):
            if name.isdigit():
                try:
                    if os.stat("/proc/" + name).st_uid != self.uid:
                        # recorded apart, still counted as other programs: the kernel's softirq threads of these
                        # CPUs, which also carry the benchmark's own loopback traffic when softirqs back up
                        if name in self.softirqd:
                            soft += proc_ticks(int(name)) or 0
                        continue
                except OSError:
                    continue
                own += proc_ticks(int(name)) or 0
        return time.monotonic(), busy, irq, own, children_cpu(), soft

    def cores(self, a, b):
        elapsed = b[0] - a[0]
        busy = (b[1] - a[1]) / self.hz
        own = (b[3] - a[3]) / self.hz + (b[4] - a[4])
        return {"others_cores": round((busy - own) / elapsed, 3), "own_cores": round(own / elapsed, 3),
                "irq_cores": round((b[2] - a[2]) / self.hz / elapsed, 3),
                "ksoftirqd_cores": round((b[5] - a[5]) / self.hz / elapsed, 3)}


class Harness:
    def __init__(self, args):
        self.framework = os.path.abspath(args.framework)
        self.out = os.path.abspath(args.out)
        self.results_dir = os.path.join(self.out, "results")
        self.pid_dir = os.path.join(self.out, "pids")
        os.makedirs(self.results_dir, exist_ok=True)
        os.makedirs(self.pid_dir, exist_ok=True)
        self.quick = args.quick
        self.warm, self.measured = (2, 3) if args.quick else (10, 20)
        self.h2load_warm = 1 if args.quick else 2
        self.starts = 2 if args.quick else 5
        self.edit_warm, self.edits = (1, 2) if args.quick else (3, 10)
        self.apps = args.apps
        self.cells = [Cell(p, n) for p in PROTOCOLS if p in args.protocols
                      for n in (args.h2_in_flight if p == "h2-tls" else [1])]
        self.endpoints = args.endpoints
        self.writes = helidon_writes(args.helidon_writes)
        self.server_prefix = ["taskset", "-c", args.server_cpus] if args.server_cpus else []
        self.client_prefix = ["taskset", "-c", args.client_cpus] if args.client_cpus else []
        client_cpus = len(cpu_list(args.client_cpus)) if args.client_cpus else os.cpu_count()
        self.h2load_threads = args.h2load_threads or max(2, client_cpus // 4)
        self.guard = Guard(cpu_list(args.guard_cpus)) if args.guard_cpus else None
        self.guard_max, self.guard_retries = args.guard_max, args.guard_retries
        self.env = dict(os.environ, PLAY_SECRET=secrets.token_hex(32))
        self.stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        environment = self.environment()
        environment.update(protocols=[c.name for c in self.cells], server_cpus=args.server_cpus,
                           client_cpus=args.client_cpus, guard_cpus=args.guard_cpus,
                           helidon_writes={a: writes_label(v) for a, v in self.writes.items()})
        self.results = {"started": self.stamp, "quick": self.quick, "environment": environment,
                        "startup": {}, "load": {}, "dev": {}}

    # -- environment ---------------------------------------------------------------------------------------------

    def environment(self):
        java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()
        oha = subprocess.run(["oha", "--version"], capture_output=True, text=True).stdout.strip() if shutil.which("oha") else None
        h2load = subprocess.run(["h2load", "--version"], capture_output=True, text=True).stdout.strip() \
            if shutil.which("h2load") else None
        commit = subprocess.run(["git", "-C", self.framework, "log", "-1", "--format=%h %s"], capture_output=True,
                                text=True).stdout.strip()
        version = open(os.path.join(self.framework, "framework/src/play/version")).read().strip()
        memory = None
        if sys.platform == "darwin":
            memory = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout) // 2**30
        elif os.path.exists("/proc/meminfo"):
            memory = int(open("/proc/meminfo").readline().split()[1]) // 2**20
        cpus = len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count()
        return {"java": java[0] if java else None, "os": "%s %s" % (platform.system(), platform.release()),
                "arch": platform.machine(), "cores": os.cpu_count(), "cpus_usable": cpus, "memory_gb": memory,
                "oha": oha, "h2load": h2load, "framework": "%s (%s)" % (version, commit), "helidon": HELIDON}

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

    def certificate(self):
        """One self-signed ECDSA P-256 certificate for localhost and 127.0.0.1, made once per output directory."""
        d = os.path.join(self.out, "tls")
        cert, key = os.path.join(d, "cert.pem"), os.path.join(d, "key.pem")
        if not (os.path.exists(cert) and os.path.exists(key)):
            os.makedirs(d, exist_ok=True)
            subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                            "-nodes", "-keyout", key, "-out", cert, "-days", "365", "-subj", "/CN=localhost",
                            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"], check=True, capture_output=True)
            os.chmod(key, 0o600)
        return cert, key

    # -- running the applications -------------------------------------------------------------------------------

    def fork_dir(self):
        root = os.path.join(self.out, "fork-bundle")
        return os.path.join(root, os.listdir(root)[0])

    def command(self, app, port, aot=None, tls=False):
        """(working directory, command line) of a production start; aot is "train" or "use" for Helidon; tls serves
        TLS, with HTTP/2 through ALPN, on the port instead of plain HTTP."""
        if app == "fork":
            cwd = self.fork_dir()
            if tls:
                cert, key = self.certificate()
                os.makedirs(os.path.join(cwd, "certs"), exist_ok=True)
                shutil.copy(cert, os.path.join(cwd, "certs", "bench.cert"))
                shutil.copy(key, os.path.join(cwd, "certs", "bench.key"))
            return cwd, self.server_prefix + ["./play", "run", "--%prod", ("--https.port=%d" if tls else "--http.port=%d") % port]
        name = {"se": "helidon-se", "mp": "helidon-mp"}[app]
        cwd = os.path.join(self.out, name, "build", "install", "bench-" + name)
        flags = [("-Dport=%d" if app == "se" else "-Dserver.port=%d") % port]
        queue, smart, nodelay = self.writes[app]
        prefix = "-D" if app == "se" else "-Dserver."
        if queue > 1:
            flags += ["%swrite-queue-length=%d" % (prefix, queue), "%ssmart-async-writes=%s" % (prefix, str(smart).lower())]
        if nodelay:
            flags.append("-Dtcp-no-delay=true" if app == "se" else "-Dserver.connection-options.tcp-no-delay=true")
        if tls:
            cert, key = self.certificate()
            if app == "se":
                flags += ["-Dtls.cert=" + cert, "-Dtls.key=" + key]
            else:
                flags += ["-Dserver.tls.private-key.pem.key.resource.path=" + key,
                          "-Dserver.tls.private-key.pem.cert-chain.resource.path=" + cert]
        main = "bench.Main" if app == "se" else "io.helidon.microprofile.cdi.Main"
        cache = {None: [], "train": ["-XX:AOTCacheOutput=app.aot", "-Dexit.on.started=!"],
                 "use": ["-XX:AOTCache=app.aot"]}[aot]
        return cwd, self.server_prefix + ["java", *cache, *flags, "-cp", "lib/*", main]

    def output(self, label):
        return os.path.join(self.results_dir, "%s-%s.out" % (label, self.stamp))

    def start(self, app, port, cmd=None, label=None, tls=False):
        cwd, line = cmd or self.command(app, port)
        f = open(self.output(label or app), "a")
        t0 = time.perf_counter()
        p = subprocess.Popen(line, cwd=cwd, env=self.env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
        with open(os.path.join(self.pid_dir, "%s-%d.pid" % (label or app, port)), "w") as pf:
            pf.write("%d\n" % p.pid)
        while time.perf_counter() < t0 + 180:
            if get(port, timeout=1.0, tls=tls)[0] == 200:
                return p, (time.perf_counter() - t0) * 1000
            if p.poll() is not None:
                raise RuntimeError("%s exited with %s before it answered" % (app, p.returncode))
            time.sleep(0.005)
        self.stop(p, port)
        raise RuntimeError("%s did not answer within 180 s" % app)

    def stop(self, p, port):
        pid = listener(port)
        if pid and owned(pid):
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
        for name in os.listdir(self.pid_dir):
            if name.endswith("-%d.pid" % port):
                os.remove(os.path.join(self.pid_dir, name))
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

    def oha_command(self, url, seconds, conns, cell):
        cmd = self.client_prefix + ["oha", "-z", "%ds" % seconds, "-c", str(conns), "--no-tui", "--output-format", "json"]
        if cell.close:
            cmd.append("--disable-keepalive")
        if cell.tls:
            cmd.append("--insecure")
        if cell.h2:
            cmd += ["--http2", "-p", str(cell.in_flight)]
        return cmd + [url]

    def oha(self, url, seconds, conns, cell):
        out = subprocess.run(self.oha_command(url, seconds, conns, cell), capture_output=True, text=True)
        return json.loads(out.stdout)

    def prove(self, cell, url):
        """The protocol the cell claims, shown by curl and by oha's own client, so a fall-back cannot pass."""
        want = "2" if cell.h2 else "1.1"
        proof = {}
        curl = self.client_prefix + ["curl", "-k", "-s", "-o", "/dev/null", "-w", "%{http_version} %{http_code}",
                                     "--http2" if cell.h2 else "--http1.1", url]
        proof["curl"] = subprocess.run(curl, capture_output=True, text=True).stdout.strip()
        # one request with the cell's own client options, which prints the request and the response it got
        debug = self.client_prefix + ["oha", "--debug", "--insecure"] + (["--http2"] if cell.h2 else []) + [url]
        text = subprocess.run(debug, capture_output=True, text=True).stdout
        versions = re.findall(r"version: (HTTP/[\d.]+)", text.split("Response", 1)[-1])
        proof["oha"] = versions[0] if versions else None
        if proof["curl"] != want + " 200" or proof["oha"] != ("HTTP/2.0" if cell.h2 else "HTTP/1.1"):
            raise RuntimeError("%s: %s is not served as HTTP/%s: curl %r, oha %r" % (cell.name, url, want, proof["curl"],
                                                                                   proof["oha"]))
        return proof

    def guarded(self, run):
        """run() under the guard: re-run while other users' programs used more than --guard-max cores of the
        benchmark CPUs, at most --guard-retries times. Returns (result, guard figures, discarded attempts)."""
        discarded = []
        for attempt in range(1 + (self.guard_retries if self.guard else 0)):
            before = self.guard.snapshot() if self.guard else None
            result = run()
            figures = self.guard.cores(before, self.guard.snapshot()) if self.guard else None
            if not figures or figures["others_cores"] <= self.guard_max:
                return result, figures, discarded
            if attempt < self.guard_retries:
                self.log("  disturbed: other programs used %.2f cores of the benchmark CPUs; measuring again"
                         % figures["others_cores"])
                discarded.append(dict(result=result, guard=figures))
        self.log("  still disturbed after %d retries: kept, flagged" % self.guard_retries)
        return result, dict(figures, disturbed=True), discarded

    def measure(self, app, port, pid, cell, ep):
        conns = 16 if ep == "sse" else 64
        url = "%s://127.0.0.1:%d/%s" % ("https" if cell.tls else "http", port, ep)
        proof = self.prove(cell, url) if cell.tls else None
        self.oha(url, self.warm, conns, cell)

        def run():
            peak, done = [0.0], threading.Event()

            def sample():
                while not done.is_set():
                    peak[0] = max(peak[0], rss_mb(pid) or 0)
                    time.sleep(0.2)

            sampler = threading.Thread(target=sample)
            server0, client0 = cpu_seconds(pid), children_cpu()
            sampler.start()
            d = self.oha(url, self.measured, conns, cell)
            done.set()
            sampler.join()
            return d, peak[0], cpu_seconds(pid) - server0, children_cpu() - client0

        (d, peak, server_cpu, client_cpu), guard, discarded = self.guarded(run)
        lat = d["latencyPercentiles"]
        status = d.get("statusCodeDistribution") or {}
        requests = sum(status.values())
        answered = sum(n for code, n in status.items() if code.startswith("2"))
        # oha's requestsPerSec counts failed requests too (a refused connect is a fast one): ok_rps counts the 2xx
        row = dict(protocol=cell.protocol, in_flight=cell.in_flight, endpoint=ep, conns=conns,
                   concurrency=conns * cell.in_flight, rps=d["summary"]["requestsPerSec"],
                   ok_rps=answered / d["summary"]["total"],
                   p50_ms=lat["p50"] * 1000 if lat["p50"] else None, p99_ms=lat["p99"] * 1000 if lat["p99"] else None,
                   success=d["summary"]["successRate"], status=d.get("statusCodeDistribution"),
                   errors=d.get("errorDistribution"), peak_rss_mb=peak,
                   server_cpu_us=server_cpu * 1e6 / requests if requests else None,
                   client_cpu_us=client_cpu * 1e6 / requests if requests else None,
                   server_cores=round(server_cpu / d["summary"]["total"], 2),
                   client_cores=round(client_cpu / d["summary"]["total"], 2),
                   guard=guard, discarded=[dict(rps=a["result"][0]["summary"]["requestsPerSec"], guard=a["guard"])
                                           for a in discarded], proof=proof)
        # a refused connect: on macOS a new connection per request runs out of ephemeral ports (TIME_WAIT)
        row["ports_exhausted"] = sum(n for e, n in (row["errors"] or {}).items() if "assign requested address" in e)
        self.log("load %-4s %-12s %-9s %8.0f req/s  p50 %s  p99 %s  ok %.4f  peak RSS %4.0f MB  server %s us/req  "
                 "oha %.1f cores%s%s" % (
                     app, cell.name, ep, row["ok_rps"], fmt_ms(row["p50_ms"]), fmt_ms(row["p99_ms"]), row["success"],
                     row["peak_rss_mb"], fmt_us(row["server_cpu_us"]), row["client_cores"],
                     "  others %.2f cores" % guard["others_cores"] if guard else "",
                     "  CLIENT OUT OF PORTS: %d connects refused" % row["ports_exhausted"] if row["ports_exhausted"] else ""))
        if cell.h2 and cell.in_flight == H2LOAD_IN_FLIGHT and shutil.which("h2load"):
            row["h2load"] = self.h2load(app, url, conns, cell, ep)
        return row

    def h2load(self, app, url, conns, cell, ep):
        """The same cell measured with nghttp2's h2load, since oha's HTTP/2 client can become the limit. No
        --log-file: writing it cut h2load to a third of its rate. Its own latency columns give p50 and p99."""
        threads = max(1, min(conns, self.h2load_threads))
        cmd = self.client_prefix + ["h2load", "-c", str(conns), "-m", str(cell.in_flight), "-t", str(threads),
                                    "-D", str(self.measured), "--warm-up-time", str(self.h2load_warm), url]

        def run():
            cpu0 = children_cpu()
            text = subprocess.run(cmd, capture_output=True, text=True).stdout
            return text, children_cpu() - cpu0

        (text, cpu), guard, discarded = self.guarded(run)
        found = lambda pattern: (re.search(pattern, text) or [None, None])[1]
        columns = re.search(r"^\s+min\s+max.*$", text, re.M)
        # 1.71 prints median, p95 and p99 on a "request :" line; 1.59 only min, max, mean and sd on "time for request:"
        values = re.search(r"^(?:time for )?request\s*:(.*)$", text, re.M)
        latency = dict(zip(columns[0].split(), values[1].split())) if columns and values else {}
        done = re.search(r"requests: (\d+) total, \d+ started, (\d+) done, (\d+) succeeded, (\d+) failed, (\d+) errored",
                         text)
        result = dict(rps=float(found(r"finished in [\d.]+s, ([\d.]+) req/s") or 0),
                      p50_ms=h2load_ms(latency.get("median")), p99_ms=h2load_ms(latency.get("p99")),
                      mean_ms=h2load_ms(latency.get("mean")),
                      requests=int(done[1]) if done else None, succeeded=int(done[3]) if done else None,
                      failed=int(done[4]) if done else None, errored=int(done[5]) if done else None,
                      protocol=found(r"Application protocol: (\S+)"), tls_protocol=found(r"TLS Protocol: (\S+)"),
                      cipher=found(r"Cipher: (\S+)"), threads=threads,
                      # h2load's CPU over its warm-up and measurement, per thread: near 1 means h2load was the limit
                      cpu_per_thread=round(cpu / (self.measured + self.h2load_warm) / threads, 2), guard=guard,
                      discarded=[dict(guard=a["guard"]) for a in discarded])
        if result["protocol"] != "h2":
            raise RuntimeError("h2load: %s negotiated %r, not h2" % (url, result["protocol"]))
        self.log("  h2load %-4s %-12s %-9s %8.0f req/s  p50 %s  p99 %s  mean %s  %s of %s succeeded, %d threads at %.2f "
                 "cores each" % (app, cell.name, ep, result["rps"], fmt_ms(result["p50_ms"]), fmt_ms(result["p99_ms"]),
                                 fmt_ms(result["mean_ms"]), result["succeeded"], result["requests"], threads,
                                 result["cpu_per_thread"]))
        return result

    def tls_info(self, app, port):
        info = {alpn: tls_probe(port, alpn) for alpn in ("h2", "http/1.1")}
        if app == "fork":
            lines = [l for l in open(self.output(app + "-load-tls")) if "TLS provider:" in l]
            info["provider"] = lines[-1].split("TLS provider:", 1)[1].strip() if lines else None
        else:
            info["provider"] = "JDK SSLEngine (Helidon has no other)"
        self.log("tls  %-4s h2: %s %s; http/1.1: %s %s; provider %s" % (
            app, info["h2"]["version"], info["h2"]["cipher"], info["http/1.1"]["version"], info["http/1.1"]["cipher"],
            info["provider"]))
        return info

    def load(self, app):
        conditions = self.settle()
        result = dict(rows=[], **conditions)
        for tls in (False, True):
            cells = [c for c in self.cells if c.tls == tls]
            if not cells:
                continue
            port = free_port()
            label = app + ("-load-tls" if tls else "-load")
            p, _ = self.start(app, port, self.command(app, port, tls=tls), label=label, tls=tls)
            pid = listener(port)
            time.sleep(2)
            result["idle_rss_tls_mb" if tls else "idle_rss_mb"] = rss_mb(pid)
            try:
                if tls:
                    result["tls"] = self.tls_info(app, port)
                for cell in cells:
                    for ep in ENDPOINTS[app]:
                        if ep in self.endpoints:
                            result["rows"].append(self.measure(app, port, pid, cell, ep))
            finally:
                self.stop(p, port)
        guards = [r["guard"]["others_cores"] for r in result["rows"] if r.get("guard")]
        if guards:
            result["others_cores_mean"] = round(statistics.mean(guards), 3)
            result["discarded"] = sum(len(r["discarded"]) for r in result["rows"])
        self.results["load"][app] = result

    def aot(self, app):
        if app == "fork":
            cwd = self.fork_dir()
            port = free_port()
            subprocess.run(self.server_prefix + ["./play", "aot-gen", "--http.port=%d" % port], cwd=cwd, env=self.env,
                           check=True, stdout=open(os.path.join(self.results_dir, "fork-aotgen-%s.out" % self.stamp), "w"),
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
        tables = summary_tables(self.results)
        with open(os.path.join(self.results_dir, "summary-%s.md" % self.stamp), "w") as f:
            f.write(describe(self.results) + "\n")
            for header, rows in tables:
                f.write("\n| " + " | ".join(header) + " |\n|" + "---|" * len(header) + "\n")
                for row in rows:
                    f.write("| " + " | ".join(row) + " |\n")
        with open(os.path.join(self.results_dir, "summary-%s.jira" % self.stamp), "w") as f:
            f.write(describe(self.results) + "\n")
            for header, rows in tables:
                f.write("\n|| " + " || ".join(header) + " ||\n")
                for row in rows:
                    f.write("| " + " | ".join(row) + " |\n")
        self.log("results: %s" % path)


def fmt_ms(value):
    return "-" if value is None else "%.2f ms" % value


def fmt_us(value):
    return "-" if value is None else "%.1f" % value


def describe(results):
    e = results["environment"]
    pinned = "; server on CPUs %s, client on %s" % (e["server_cpus"], e["client_cpus"]) if e.get("server_cpus") else ""
    writes = "; Helidon writes: %s" % ", ".join("%s %s" % (LABELS[a], w) for a, w in e["helidon_writes"].items()) \
        if e.get("helidon_writes") else ""
    return "%s; %s %s, %s cores, %s GB; %s; %s; framework %s; Helidon %s%s%s; run %s%s" % (
        e["java"], e["os"], e["arch"], e["cores"], e["memory_gb"], e["oha"], e.get("h2load"), e["framework"],
        e["helidon"], writes, pinned, results["started"], " (quick: not for the record)" if results["quick"] else "")


def load_row(results, app, protocol, in_flight, ep):
    return next((r for r in results["load"].get(app, {}).get("rows", []) if r.get("protocol", "h1") == protocol
                 and r.get("in_flight", 1) == in_flight and r["endpoint"] == ep), None)


def load_cell(row):
    """2xx responses per second and p99, with the success rate when requests failed."""
    if not row:
        return "-"
    text = "%.0f req/s, p99 %s" % (row.get("ok_rps", row["rps"]), fmt_ms(row["p99_ms"]))
    return text + (", ok %.1f%%" % (row["success"] * 100) if row["success"] < 0.999 else "")


def h2load_cell(row):
    if not row or not row.get("h2load"):
        return "-"
    h = row["h2load"]
    latency = "p99 %s" % fmt_ms(h["p99_ms"]) if h.get("p99_ms") is not None else "mean %s" % fmt_ms(h.get("mean_ms"))
    return "%.0f req/s, %s (oha %.0f)" % (h["rps"], latency, row.get("ok_rps", row["rps"]))


def summary_tables(results):
    """[(header, rows)]: the load cells, the h2load cross-check, the TLS each side negotiated, and the rest."""
    tables = []
    load = results["load"]
    keys = sorted({(r.get("protocol", "h1"), r.get("in_flight", 1)) for a in load for r in load[a]["rows"]},
                  key=lambda k: (list(PROTOCOLS).index(k[0]), k[1]))
    header = ["Protocol", "Connections", "Endpoint"] + [LABELS[a] for a in APPS]
    rows, h2rows = [], []
    for protocol, in_flight in keys:
        for ep in ALL_ENDPOINTS:
            found = {a: load_row(results, a, protocol, in_flight, ep) for a in APPS}
            if not any(found.values()):
                continue
            conns = next(r["conns"] for r in found.values() if r)
            prefix = [Cell(protocol, in_flight).label(), "%d" % conns if in_flight == 1 else "%d x %d = %d" % (
                conns, in_flight, conns * in_flight), ep]
            rows.append(prefix + [load_cell(r) for r in found.values()])
            if any(r and r.get("h2load") for r in found.values()):
                h2rows.append(prefix + [h2load_cell(r) for r in found.values()])
    if rows:
        tables.append((header, rows))
    if h2rows:
        tables.append((["h2load cross-check", "Connections", "Endpoint"] + [LABELS[a] for a in APPS], h2rows))
    tls = [[LABELS[a], "%s %s" % (load[a]["tls"]["h2"]["version"], load[a]["tls"]["h2"]["cipher"]),
            "%s %s" % (load[a]["tls"]["http/1.1"]["version"], load[a]["tls"]["http/1.1"]["cipher"]),
            load[a]["tls"]["provider"] or "-"] for a in APPS if a in load and load[a].get("tls")]
    if tls:
        tables.append((["TLS", "with h2", "with http/1.1", "Provider"], tls))
    rows = []
    for label, key in (("startup, no AOT cache", ""), ("startup, AOT cache", "-aot")):
        cells = ["%.0f ms" % results["startup"][a + key]["median_ms"] if a + key in results["startup"] else "-" for a in APPS]
        if any(c != "-" for c in cells):
            rows.append([label] + cells)
    cells = ["%.0f MB" % load[a]["idle_rss_mb"] if load.get(a, {}).get("idle_rss_mb") else "-" for a in APPS]
    if any(c != "-" for c in cells):
        rows.append(["memory idle after start"] + cells)
        rows.append(["peak memory under load"] + ["%.0f to %.0f MB" % (min(r["peak_rss_mb"] for r in load[a]["rows"]),
                                                                    max(r["peak_rss_mb"] for r in load[a]["rows"]))
                                                  if load.get(a, {}).get("rows") else "-" for a in APPS])
    dev = results["dev"]
    for label, key in (("edit to new response, body", "median_body_ms"), ("edit to new response, new method", "median_sig_ms")):
        cells = ["%.0f ms" % dev[a][key] if a in dev else "-" for a in APPS]
        if any(c != "-" for c in cells):
            rows.append([label] + cells)
    if rows:
        tables.append((["Metric"] + [LABELS[a] for a in APPS], rows))
    return tables


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--framework", required=True, help="a built checkout of this repository")
    parser.add_argument("--out", default=os.path.join(HERE, "out"), help="where the copies and results go")
    parser.add_argument("--steps", nargs="+", default=["build", "startup", "load", "aot", "dev"],
                        choices=["build", "startup", "load", "aot", "dev"])
    parser.add_argument("--apps", nargs="+", default=list(APPS), choices=list(APPS))
    parser.add_argument("--quick", action="store_true", help="short runs that check the pipeline, not for the record")
    parser.add_argument("--protocols", nargs="+", default=list(PROTOCOLS), choices=list(PROTOCOLS),
                        help="the load cells, all of them by default")
    parser.add_argument("--h2-in-flight", nargs="+", type=int, default=[1, 4, 16], metavar="N",
                        help="requests in flight per connection in the HTTP/2 cells (oha -p, h2load -m)")
    parser.add_argument("--endpoints", nargs="+", default=list(ALL_ENDPOINTS), choices=list(ALL_ENDPOINTS))
    parser.add_argument("--helidon-writes", nargs="+", metavar="[APP=]MODE[+nodelay]",
                        help="Helidon's listener writes: direct (its default), async:N (write-queue-length N) or "
                             "smart:N (and smart-async-writes), +nodelay for TCP_NODELAY (off by default), for se and "
                             "mp, or one of them with se= or mp=")
    parser.add_argument("--h2load-threads", type=int, metavar="N",
                        help="h2load's threads in the cross-check: a quarter of the client's CPUs, at least 2, by default")
    parser.add_argument("--server-cpus", help="Linux: taskset CPU list for the servers under test")
    parser.add_argument("--client-cpus", help="Linux: taskset CPU list for oha, h2load and curl")
    parser.add_argument("--guard-cpus", help="Linux: the benchmark CPUs whose use by other users' programs each "
                                             "measurement records, and re-measures above --guard-max")
    parser.add_argument("--guard-max", type=float, default=0.5, help="cores' worth, on average (0.5)")
    parser.add_argument("--guard-retries", type=int, default=2, help="re-measurements of a disturbed cell (2)")
    args = parser.parse_args()
    tools = ["java", "oha", "git", "unzip"]
    if any(p.endswith("-tls") for p in args.protocols) and "load" in args.steps:
        tools += ["openssl", "curl"]
    if args.server_cpus or args.client_cpus:
        tools.append("taskset")
    for tool in tools:
        if not shutil.which(tool):
            raise SystemExit("%s is not on PATH" % tool)
    if args.guard_cpus and not os.path.exists("/proc/stat"):
        raise SystemExit("--guard-cpus reads /proc/stat: Linux only")
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

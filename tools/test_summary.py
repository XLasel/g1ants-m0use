#!/usr/bin/env python3
"""Run Kotlin summary tests with JDK 17+, without Gradle or Android SDK.

Pinned compiler/test jars are fetched from Maven Central and SHA-1 checked.
Android 4.1 stubs are compile-only API compatibility checks, not an emulator.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://repo.maven.apache.org/maven2/"
ARTIFACTS = [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.20"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.20"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.20"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ("org.jetbrains", "annotations", "13.0"),
    ("org.json", "json", "20240303"),
    ("junit", "junit", "4.13.2"),
    ("org.hamcrest", "hamcrest-core", "1.3"),
    ("com.google.android", "android", "4.1.1.4"),
]


def fetch(cache: Path, artifact: tuple[str, str, str]) -> Path:
    group, name, version = artifact
    filename = f"{name}-{version}.jar"
    url = BASE + f"{group.replace('.', '/')}/{name}/{version}/{filename}"
    path = cache / filename
    with urllib.request.urlopen(url + ".sha1", timeout=30) as response:
        expected = response.read().decode().split()[0].lower()
    if path.exists() and hashlib.sha1(path.read_bytes()).hexdigest() == expected:
        return path
    part = path.with_suffix(".part")
    try:
        print("Downloading", filename, flush=True)
        with urllib.request.urlopen(url, timeout=60) as response, part.open("wb") as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha1(part.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Checksum mismatch: " + filename)
        part.replace(path)
    finally:
        part.unlink(missing_ok=True)
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path(tempfile.gettempdir()) / "nuclear-summary-toolchain")
    parser.add_argument("--native-library-dir", type=Path, help="Optional host JNI library directory; does not run a model")
    parser.add_argument("--model", type=Path, help="Run real GGUF host inference after unit tests")
    parser.add_argument("--transcript", type=Path, help="Input JSON in the task's transcript format")
    parser.add_argument("--report", type=Path, default=ROOT / "artifacts/test-results/summary-host.json")
    parser.add_argument("--context-size", type=int, default=4096)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if bool(args.model) != bool(args.transcript) or (args.model and not args.native_library_dir):
        parser.error("--model and --transcript require each other and --native-library-dir")
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    if not shutil.which("java"):
        raise SystemExit("JDK 17+ is required")
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        jars = list(pool.map(lambda item: fetch(args.cache_dir, item), ARTIFACTS))
    by_name = {artifact[1]: path for artifact, path in zip(ARTIFACTS, jars)}
    core = ROOT / "src/android/summary/src"
    android = ROOT / "src/android/summary-android/src"
    sources = sorted(core.rglob("*.kt")) + sorted((android / "main/kotlin").rglob("*.kt")) + sorted((android / "test/kotlin").rglob("*.kt"))
    with tempfile.TemporaryDirectory(prefix="nuclear-summary-test-") as directory:
        classes = Path(directory) / "classes"
        classes.mkdir()
        compile_classpath = os.pathsep.join(str(by_name[name]) for name in ["android", "json", "kotlin-stdlib", "junit", "hamcrest-core", "annotations", "kotlinx-coroutines-core-jvm"])
        subprocess.run([
            "java", "-Xmx1g", "-cp", os.pathsep.join(map(str, jars)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", compile_classpath, "-d", str(classes),
            *map(str, sources),
        ], check=True)
        runtime_classpath = os.pathsep.join(map(str, [classes, by_name["json"], by_name["kotlin-stdlib"], by_name["junit"], by_name["hamcrest-core"], by_name["kotlinx-coroutines-core-jvm"]]))
        subprocess.run(["java", "-cp", runtime_classpath, "org.junit.runner.JUnitCore", "ru.stenographer.summary.LocalSummarizerTest", "ru.stenographer.summary.android.SummaryWorkerTest"], check=True)
        if args.native_library_dir:
            subprocess.run(["java", "-Djava.library.path=" + str(args.native_library_dir.resolve()), "-cp", runtime_classpath, "ru.stenographer.summary.android.NativeBridgeHostSmoke"], check=True)
        if args.model:
            subprocess.run(["java", "-Xmx2g", "-Djava.library.path=" + str(args.native_library_dir.resolve()),
                "-cp", runtime_classpath, "ru.stenographer.summary.android.SummaryHostBenchmark",
                str(args.model.resolve()), str(args.transcript.resolve()), str(args.report.resolve()),
                str(args.context_size), str(args.threads)], check=True)


if __name__ == "__main__":
    main()

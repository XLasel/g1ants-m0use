#!/usr/bin/env python3
"""Fetch pinned llama.cpp sources for an Android build (not used at runtime)."""
from pathlib import Path
import subprocess
import tempfile

PIN = "cf659f240b232e15700b1721bbb43c1d973c52e9"
DESTINATION = Path(__file__).resolve().parents[1] / "vendor/llama.cpp"


def git(*args, cwd=None):
    try:
        return subprocess.run(["git", *args], cwd=cwd, check=True, text=True, capture_output=True, timeout=180)
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr.strip() or "Git dependency preparation failed") from error


def main():
    if DESTINATION.exists():
        actual = git("rev-parse", "HEAD", cwd=DESTINATION).stdout.strip()
        if actual != PIN:
            raise SystemExit("Existing llama.cpp has a different revision; move it aside explicitly first")
        git("diff", "--quiet", "HEAD", cwd=DESTINATION)
        print("Pinned llama.cpp ready:", PIN)
        return
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=DESTINATION.parent, prefix="llama-fetch-") as directory:
        source = Path(directory) / "source"
        source.mkdir()
        git("init", cwd=source)
        git("remote", "add", "origin", "https://github.com/ggml-org/llama.cpp.git", cwd=source)
        git("sparse-checkout", "init", "--cone", cwd=source)
        git("sparse-checkout", "set", "src", "include", "ggml", "cmake", "vendor", "common", cwd=source)
        git("fetch", "--filter=blob:none", "--depth=1", "origin", PIN, cwd=source)
        git("checkout", "--detach", "FETCH_HEAD", cwd=source)
        assert git("rev-parse", "HEAD", cwd=source).stdout.strip() == PIN
        source.replace(DESTINATION)
    print("Pinned llama.cpp ready:", PIN)


if __name__ == "__main__":
    main()

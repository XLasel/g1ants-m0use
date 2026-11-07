#!/usr/bin/env python3
"""Download the pinned official Qwen GGUF before offline testing; never run from the APK."""
from pathlib import Path
import argparse
import hashlib
import urllib.request

REVISION = "91cad51170dc346986eccefdc2dd33a9da36ead9"
FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
SIZE = 1117320736
URL = f"https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/{REVISION}/{FILENAME}?download=true"


def verified(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest() == SHA256


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "models" / FILENAME)
    args = parser.parse_args()
    target = args.output.resolve()
    if verified(target):
        print("Verified:", target)
        return
    if target.exists():
        raise SystemExit("Existing file differs from the pinned model; choose a new output path")
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    digest = hashlib.sha256()
    copied = 0
    try:
        with urllib.request.urlopen(URL, timeout=60) as source, part.open("wb") as output:
            for chunk in iter(lambda: source.read(1 << 20), b""):
                output.write(chunk)
                digest.update(chunk)
                copied += len(chunk)
                if copied % (64 << 20) == 0:
                    print(f"{copied * 100 // SIZE}%", flush=True)
        if copied != SIZE or digest.hexdigest() != SHA256:
            raise RuntimeError("Model size or SHA-256 mismatch")
        part.replace(target)
    finally:
        part.unlink(missing_ok=True)
    print("Verified:", target)


if __name__ == "__main__":
    main()

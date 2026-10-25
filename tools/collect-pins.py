#!/usr/bin/env python3
"""Collects GrapheneOS's signing keys for every supported phone model, for +PLUS's pins (core/src/main/resources/pins/).

For each model: download the current stable full OTA from releases.grapheneos.org, take the AVB public key (vbmeta's
public_key, = grapheneos_avb_pkmd.bin) and the OTA certificate (META-INF/com/android/otacert), and accept them only if
  1. the AVB key's SHA-256 is one of the verified boot key hashes GrapheneOS publishes on its CLI install guide (which
     also names the model), and
  2. avbroot verifies the whole OTA (signature, payload, every partition) against both keys.
The OTA is deleted afterwards. Results: <out>/pins/<codename>/ and <out>/pins.json.

    collect-pins.py <out dir> <avbroot> [codename ...]      (default: every model on grapheneos.org/releases)
"""
import hashlib
import html
import json
import re
import subprocess
import sys
import tomllib
import urllib.request
import zipfile
from pathlib import Path

RELEASES = "https://releases.grapheneos.org/"


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def published_hashes():
    """{sha256: model name} from the "Verified boot key hash" list of the CLI install guide."""
    page = get("https://grapheneos.org/install/cli").decode()
    section = page[page.index('id=verified-boot-key-hash'):page.index('id=hardware-based-attestation')]
    return {h: html.unescape(name) for name, h in re.findall(r"<li>([^<:]+): <code>([0-9a-f]{64})</code></li>", section)}


def codenames():
    page = get("https://grapheneos.org/releases").decode()
    return sorted(set(re.findall(r"\b([a-z]+)-stable\b", page)))


def find_key(o, key):
    if isinstance(o, dict):
        if key in o:
            return o[key]
        o = list(o.values())
    if isinstance(o, list):
        for v in o:
            r = find_key(v, key)
            if r is not None:
                return r
    return None


def collect(device, out, avbroot, hashes):
    line = get(f"{RELEASES}{device}-stable").decode().split()
    if len(line) != 4 or line[2] != device:
        raise RuntimeError(f"unexpected release metadata: {line}")
    build = line[0]
    work = out / "work" / device
    work.mkdir(parents=True, exist_ok=True)
    ota = work / f"{device}-ota_update-{build}.zip"
    if not ota.exists():
        subprocess.run(["curl", "-sfL", "-C", "-", "-o", f"{ota}.part", f"{RELEASES}{ota.name}"], check=True)
        Path(f"{ota}.part").rename(ota)
    with zipfile.ZipFile(ota) as z:
        cert = z.read("META-INF/com/android/otacert")
    subprocess.run([avbroot, "ota", "extract", "-i", ota, "-d", work / "ex", "-p", "vbmeta"], check=True, capture_output=True)
    subprocess.run([avbroot, "avb", "unpack", "-i", work / "ex" / "vbmeta.img", "--output-info", work / "vbmeta.toml", "-q"],
                   check=True, capture_output=True)
    pkmd = bytes.fromhex(find_key(tomllib.loads((work / "vbmeta.toml").read_text()), "public_key"))
    digest = hashlib.sha256(pkmd).hexdigest()
    name = hashes.get(digest)
    if name is None:
        raise RuntimeError(f"AVB key {digest} is not one of GrapheneOS's published verified boot key hashes")
    pins = out / "pins" / device
    pins.mkdir(parents=True, exist_ok=True)
    (pins / "grapheneos_avb_pkmd.bin").write_bytes(pkmd)
    (pins / "grapheneos_ota.x509.pem").write_bytes(cert)
    v = subprocess.run([avbroot, "ota", "verify", "--input", ota, "--public-key-avb", pins / "grapheneos_avb_pkmd.bin",
                        "--cert-ota", pins / "grapheneos_ota.x509.pem"], capture_output=True, text=True)
    if v.returncode != 0:
        raise RuntimeError(f"avbroot ota verify failed: {v.stderr.strip().splitlines()[-1:]}")
    subject = subprocess.run(["openssl", "x509", "-noout", "-subject", "-fingerprint", "-sha256", "-in", pins / "grapheneos_ota.x509.pem"],
                             capture_output=True, text=True, check=True).stdout.split("\n")
    ota.unlink()
    return {"device": device, "name": name, "build": build, "avb_key_sha256": digest,
            "ota_cert_sha256": hashlib.sha256(cert).hexdigest(), "ota_cert": [s.strip() for s in subject if s.strip()]}


def main():
    out, avbroot = Path(sys.argv[1]), sys.argv[2]
    hashes = published_hashes()
    devices = sys.argv[3:] or codenames()
    print(f"{len(hashes)} published hashes; models: {' '.join(devices)}", flush=True)
    results_file = out / "pins.json"
    results = json.loads(results_file.read_text()) if results_file.exists() else {}
    for d in devices:
        if results.get(d, {}).get("ok"):
            print(f"{d}: already done", flush=True)
            continue
        try:
            r = collect(d, out, avbroot, hashes) | {"ok": True}
            print(f"{d}: OK {r['name']} build {r['build']} avb {r['avb_key_sha256'][:16]}", flush=True)
        except Exception as e:
            r = {"device": d, "ok": False, "error": str(e)}
            print(f"{d}: FAILED {e}", flush=True)
        results[d] = r
        results_file.write_text(json.dumps(results, indent=2) + "\n")
    print("done:", sum(1 for r in results.values() if r.get("ok")), "of", len(devices), "ok", flush=True)


if __name__ == "__main__":
    main()

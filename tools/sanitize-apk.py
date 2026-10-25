#!/usr/bin/env python3
"""Sanitize an APK for the public catalog (no rebuild): drop library build metadata (META-INF/maven/**, which names
the libraries' developers by email) and the old signature, and replace email addresses in the licence notices
(res/raw/third_party_licenses) with placeholder text of the same length, so the byte offsets in
third_party_license_metadata stay valid. Every other entry is copied byte for byte with its compression method.
The result must be zipaligned and signed again (with the same key, so phones accept it as an update).

    sanitize-apk.py <in.apk> <out-unsigned.apk>
"""
import re
import sys
import zipfile

EMAIL = re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
NOTICES = "res/raw/third_party_licenses"
DROP = re.compile(r"^META-INF/(maven/|[^/]+\.(SF|RSA|EC|DSA)$|MANIFEST\.MF$)")


def placeholder(n):
    text = b"(address removed)" if n >= 17 else b"(removed)" if n >= 9 else b"-"
    return (text + b" " * n)[:n]


def main(src, dst):
    dropped, replaced = 0, 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            if DROP.match(info.filename):
                dropped += 1
                continue
            data = zin.read(info)
            if info.filename == NOTICES:
                before = len(data)
                data, replaced = EMAIL.subn(lambda m: placeholder(len(m.group(0))), data)
                assert len(data) == before, "licence notices changed length"
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            zout.writestr(out, data)
    print(f"dropped {dropped} entries (library build metadata, old signature); replaced {replaced} addresses in the notices")


if __name__ == "__main__":
    main(*sys.argv[1:])

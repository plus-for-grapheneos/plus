#!/usr/bin/env bash
# Publish the +PLUS add-on catalog into a local git checkout of the catalog site (GitHub Pages).
#
# Runs where the catalog key is kept (never on GitHub). It signs index.json and models.json, copies the APKs the
# index names, drops APKs it no longer names, and commits. It pushes only with --push.
#
#   tools/publish-catalog.sh --site <catalog checkout> --key <catalog key, PKCS#8 PEM> --draft <draft json>
#       --apks <apk dir> --models <models draft json> --base-url <https://…/> --build-tools <dir> [--push]
#
# The commit has no email address: author and committer are "+PLUS for GrapheneOS <>".
set -euo pipefail

site= key= draft= apks= models= base= bt= push=0
plusbuild=${PLUSBUILD:-plusbuild}
while [ $# -gt 0 ]; do
    case "$1" in
        --site) site=$2; shift 2 ;;
        --key) key=$2; shift 2 ;;
        --draft) draft=$2; shift 2 ;;
        --apks) apks=$2; shift 2 ;;
        --models) models=$2; shift 2 ;;
        --base-url) base=$2; shift 2 ;;
        --build-tools) bt=$2; shift 2 ;;
        --push) push=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
for v in site key draft apks models base bt; do
    [ -n "${!v}" ] || { echo "missing --${v/bt/build-tools}" >&2; exit 2; }
done
case "$base" in https://*/) ;; *) echo "--base-url must be https:// and end with /" >&2; exit 2 ;; esac
[ -d "$site/.git" ] || { echo "$site is not a git checkout" >&2; exit 2; }

# The key must never land in the site.
case "$(cd "$(dirname "$key")" && pwd)/" in "$(cd "$site" && pwd)/"*) echo "the key is inside the site" >&2; exit 2 ;; esac

"$plusbuild" publish --draft "$draft" --apks "$apks" --key "$key" --out "$site" \
    --aapt2 "$bt/aapt2" --apksigner "$bt/apksigner" --base-url "$base"
"$plusbuild" publish-models --models "$models" --key "$key" --out "$site"

# Keep only the APKs the new index names (older ones stay in the git history).
keep=$(python3 -c 'import json,sys; print("\n".join(a["apk"] for a in json.load(open(sys.argv[1]))["apps"]))' "$site/index.json")
for f in "$site"/*.apk; do
    [ -e "$f" ] || continue
    if ! grep -qxF "$(basename "$f")" <<<"$keep"; then rm -f "$f"; fi
done

touch "$site/.nojekyll"
# Nothing private may be published. Text files: no key material, no email addresses, no LAN addresses. APKs: no LAN
# addresses or development hosts (third-party library metadata inside an APK may name its authors' addresses).
private='192\.168\.[0-9]+\.[0-9]+|10\.10\.[0-9]+\.[0-9]+|:8800/'
found=0
grep -rIlE --exclude-dir=.git -e "-----BEGIN [A-Z ]*PRIVATE KEY-----|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}|$private" \
    "$site" && found=1 || [ $? -eq 1 ] || exit 1
for f in "$site"/*.apk; do
    [ -e "$f" ] || continue
    # grep -c reads to the end (grep -q would stop early and fail the pipeline with SIGPIPE under pipefail).
    if unzip -p "$f" | grep -caE "$private" >/dev/null; then echo "$f"; found=1; fi
done
[ $found -eq 0 ] || { echo "refusing to commit: the files above hold private material" >&2; exit 1; }

serial=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["serial"])' "$site/index.json")
git -C "$site" add -A
git -C "$site" -c user.name="+PLUS for GrapheneOS" -c user.email= commit -q -m "Catalog serial $serial" || echo "nothing changed"
git -C "$site" log -1 --format='%h %s'
[ $push -eq 1 ] && git -C "$site" push
exit 0

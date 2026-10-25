#!/usr/bin/env bash
# Downloads the pinned avbroot and afsr releases (both GPL-3.0, by chenxiaolong) and checks their sha256.
#   android : app/src/main/jniLibs/arm64-v8a/lib{avbroot,afsr}.so   (+PLUS runs them from its native library dir)
#   windows : tools/bin/windows/{avbroot,afsr}.exe                   (bundled with plus-setup)
#   linux   : tools/bin/linux/{avbroot,afsr}                         (plusbuild on a Linux box, CI)
# Usage: tools/fetch-binaries.sh [android] [windows] [linux]   (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."

AVB=3.37.0 AFSR=2.0.0
declare -A SHA=(
  [avbroot-$AVB-aarch64-linux-android31.zip]=0d3c1bb5cb3b973a696ce7cff302a8e2d122676d4754169eb31b3e45424cc185
  [afsr-$AFSR-aarch64-linux-android31.zip]=75110b5a542a8aa8dca400139fcbc9f2bde4dd9ad40274584fe2de117347f694
  [avbroot-$AVB-x86_64-pc-windows-msvc.zip]=33bb4f1a4b3a3b72fb1e199aeb6645d89464293379a9d2e375b7576466d2a1f2
  [afsr-$AFSR-x86_64-pc-windows-gnu.zip]=e4a42bcf9368b161b8e0061365c512b884e7f56422bff1db8bfca07f2ce90283
  [avbroot-$AVB-x86_64-unknown-linux-gnu.zip]=0fefdcd6396017229270e7494ed79340c690c39f593d3591ed25aeaad9b89b50
  [afsr-$AFSR-x86_64-unknown-linux-gnu.zip]=bb30be01ff336a8ac7bc204d0623385f5257bb46ffc38a888af553e059acdc0e
)

cache=build/tool-downloads
mkdir -p "$cache"

fetch() { # <tool> <version> <target> -> path of the extracted directory
  local tool=$1 ver=$2 target=$3 zip="$1-$2-$3.zip"
  if [ ! -f "$cache/$zip" ]; then
    curl -fsSL -o "$cache/$zip.part" "https://github.com/chenxiaolong/$tool/releases/download/v$ver/$zip"
    mv "$cache/$zip.part" "$cache/$zip"
  fi
  echo "${SHA[$zip]}  $cache/$zip" | sha256sum -c --quiet - || { echo "sha256 mismatch for $zip" >&2; rm -f "$cache/$zip"; exit 1; }
  rm -rf "$cache/${zip%.zip}" && unzip -q -o "$cache/$zip" -d "$cache/${zip%.zip}"
  echo "$cache/${zip%.zip}"
}

targets=("$@"); [ ${#targets[@]} -eq 0 ] && targets=(android windows linux)
for t in "${targets[@]}"; do
  case $t in
    android)
      d=app/src/main/jniLibs/arm64-v8a; mkdir -p $d
      install -m 755 "$(fetch avbroot $AVB aarch64-linux-android31)/avbroot" $d/libavbroot.so
      install -m 755 "$(fetch afsr $AFSR aarch64-linux-android31)/afsr" $d/libafsr.so ;;
    windows)
      d=tools/bin/windows; mkdir -p $d
      cp "$(fetch avbroot $AVB x86_64-pc-windows-msvc)/avbroot.exe" $d/
      cp "$(fetch afsr $AFSR x86_64-pc-windows-gnu)/afsr.exe" $d/ ;;
    linux)
      d=tools/bin/linux; mkdir -p $d
      install -m 755 "$(fetch avbroot $AVB x86_64-unknown-linux-gnu)/avbroot" $d/avbroot
      install -m 755 "$(fetch afsr $AFSR x86_64-unknown-linux-gnu)/afsr" $d/afsr ;;
    *) echo "unknown target $t" >&2; exit 2 ;;
  esac
  echo "fetched $t"
done

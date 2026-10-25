#!/bin/bash
# Downloads the setup page's binary assets from the published site: the WebAssembly tools (built from wasm-build/)
# and the +PLUS app the page puts into the image. Run it before serving this directory locally.
set -euo pipefail
SITE=${SITE:-https://plus-for-grapheneos.github.io/install}
cd "$(dirname "$0")"
mkdir -p tools assets/plus
curl -fL -o tools/avbroot.wasm "$SITE/tools/avbroot.wasm"
curl -fL -o tools/afsr.wasm "$SITE/tools/afsr.wasm"
curl -fL -o assets/plus/Plus.apk "$SITE/assets/plus/Plus.apk"

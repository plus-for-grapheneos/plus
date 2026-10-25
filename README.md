# +PLUS for GrapheneOS

A self-signing, automatic update engine for GrapheneOS, featuring call screening. On your phone, +PLUS takes each
official GrapheneOS update, adds the plus-ups you chose, signs the result with a key only you hold, and installs it.
Site: https://plus-for-grapheneos.github.io/ (public alpha: an MVP demo).

## What is here

| Path | What |
|---|---|
| `app/` | +PLUS, the on-phone update engine and plus-up store (`app.seamlessupdate.client`, version 14 in the catalog) |
| `ai/` | +PLUS AI, the on-device language model service (`app.plusos.ai`, version 6 in the catalog) |
| `core/` | The shared logic: update checks, GrapheneOS release verification, image pipeline, catalog verification, keys |
| `cli/` | `plusbuild`: builds images on a computer and publishes the signed catalog |
| `etc/system/` | The system XMLs +PLUS needs (privileged permissions, default permissions, sysconfig) |
| `web-setup/` | The setup page (served at https://plus-for-grapheneos.github.io/install/), its tests, and `wasm-build/`: the patches and script that build avbroot and afsr for WebAssembly |
| `tools/` | Pinning GrapheneOS's keys per model, fetching avbroot/afsr, publishing the catalog, sanitizing APKs |
| `docs/security-notes.md` | The security design and its limits |

The call screening plus-up, Phone+, is in https://github.com/plus-for-grapheneos/phone-plus.

## Building

- Apps and core: `./gradlew :app:assembleRelease :ai:assembleRelease :core:test` (JDK 17). Before building +PLUS,
  `tools/fetch-binaries.sh android` downloads the pinned avbroot and afsr release binaries it runs.
- Setup page: `cd web-setup && npm test` (Node 22), then `./fetch-assets.sh` and serve the directory over
  `http://localhost`.

## Third-party code

- avbroot 3.37.0 and afsr 2.0.0 by Andrew Gunnerson (GPL-3.0): https://github.com/chenxiaolong/avbroot,
  https://github.com/chenxiaolong/afsr. +PLUS runs the unchanged release binaries; the setup page runs them compiled
  to WebAssembly with the changes in `web-setup/wasm-build/`.
- `web-setup/vendor/`: browser_wasi_shim 0.4.2 (MIT or Apache-2.0), fastboot.js 1.1.3 (MIT; bundles zip.js,
  BSD-3-Clause), qrcode-generator 2.0.4 (MIT), unchanged, with their licences (contact addresses removed).
- +PLUS AI uses LiteRT-LM (Apache-2.0), https://github.com/google-ai-edge/LiteRT-LM.

## Licence

GNU General Public License, version 3 or (at your option) any later version (`LICENSE`). Third-party code keeps its
own licence.

+PLUS for GrapheneOS is an independent project, not affiliated with or endorsed by the GrapheneOS project.

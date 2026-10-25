# +PLUS setup page (test version)

A static web page that does what `plus-setup` does, inside the browser: the owner picks the official GrapheneOS update
file, the page creates the owner key (backup text, printable sheet, QR code), builds the first image with +PLUS
(avbroot and afsr compiled to WebAssembly, `wasm-build/`), and loads it onto the phone over WebUSB.
Nothing is uploaded. Every model GrapheneOS supports (Pixel 6 and later), as +PLUS 12; each model's GrapheneOS keys
are in `assets/pins/<codename>/` (collected and verified by `tools/collect-pins.py`).

Requirements: desktop Chrome or Edge (WebUSB), opened from `https://` or `localhost`, about 8 GB of free disk space the
browser may use (measured peak 6.55 GB), 20 to 45 minutes for the build.

## Run it locally

```
./fetch-assets.sh            # the binary assets below, from the published site
python3 -m http.server 8000  # in this directory, then open http://localhost:8000/
```

Binary assets (not in git): `tools/avbroot.wasm`, `tools/afsr.wasm` (built by `wasm-build/build.sh`) and
`assets/plus/Plus.apk` (the +PLUS build the store serves; version in `assets/config.json`). For GitHub Pages they
would be published by the release workflow, not committed.

## How it is put together

| File | What it does |
|---|---|
| `index.html`, `app.js`, `style.css` | the steps: checks, phone model, GrapheneOS file, key, build, load, finish |
| `worker.js` | runs the build off the page's main thread; the signed OTA and the images to load go straight into storage files of their own (a pool opened before the tools start), so nothing is copied; maps large images' zero blocks for sparse sending |
| `lib/build.js` | +PLUS's `Pipeline.kt` for the first image: same tool commands, the edit step in JavaScript |
| `lib/store.js` | storage for everything the tools create: one OPFS file opened once, handed out in extents, so every WASI call stays synchronous; plugs into browser_wasi_shim's Inode/Fd classes |
| `lib/run.js`, `lib/source.js` | run one WebAssembly tool; read-only inputs (the picked file is read in place) |
| `lib/owner.js` | key, backup text, QR string, AVB key blob, owner certificate, build manifest: byte-identical to the Kotlin core (`test/vectors.json`, written and re-checked by `core`'s `WebVectorsTest`) |
| `lib/edit.js`, `lib/zip.js`, `lib/sha256.js` | fs_metadata edit (as `FsEdit.kt`), APK reading, incremental SHA-256 |
| `lib/sparse.js` | Android sparse format: the page builds large images as headers plus slices of the raw file (no copy), so the flasher sends them in pieces |
| `lib/flash.js` | the flashing sequence of `Fastboot.flashFirstImage` (Kotlin), driven by avbroot's `fastboot-info.txt` |
| `vendor/` | browser_wasi_shim 0.4.2, fastboot.js 1.1.3 (android-fastboot), qrcode-generator 2.0.4: unchanged |

Two fixes for browser_wasi_shim live in `lib/store.js`: its `Filestat` writes timestamps at the wrong offsets
(38/46/52 instead of 40/48/56, corrupting the size when a time is set), and it refuses `O_EXCL` without `O_CREAT`,
which e2fsprogs' mke2fs uses to open the image it writes. Also: it logs every lookup unless `debug: false` is passed.

## Tests

`npm test` (Node 22+): storage, key and backup, certificate, manifest, TOML edit, zip, SHA-256, sparse writer,
flashing sequence.

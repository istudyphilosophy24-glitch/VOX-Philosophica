# VOX PHILOSOPHICA 0.4.0 — Reader & Listening Studio

Native, offline-first Android app for philosophical and academic audiobooks.

## New in 0.4: document engine

- **EPUB** — reads chapters in spine order, EPUB 3 nav / EPUB 2 NCX titles, OPF book titles and creator metadata, common cover formats, relative resource paths, paragraph/heading/list boundaries.
- **PDF** — extracts *existing selectable text* in page-ordered groups of eight using PDFBox Android. Image-only scanned pages, complex mathematics and multi-column layouts may still need specialist OCR/layout processing.
- **DOCX** — reads paragraphs, tabs and line breaks; attempts to split on named Heading/Title paragraph styles.
- **TXT, Markdown, HTML** — supported for locally imported books.
- Guardrails include bounded archive extraction, disabled XML external entities, document-size limits and private storage. The app does not upload books.

### New music-library-inspired interface

- Black/charcoal/green theme with original VOX identity (not Spotify branding).
- Home listening hero, recently added book artwork shelf and academic studio callout.
- Searchable library with cover artwork, metadata, section counts and book removal.
- Persistent mini-player and saved book/chapter state.
- Dedicated album-style book player, voice configuration, chapter chooser, reading-text drawer, progress status and next-sections preview.

## Kokoro offline speech (preserved)

Includes **INT8 Kokoro v1.0 and v1.1** archives packed into the same ARM64 debug APK, downloaded and hash-validated by GitHub Actions at build time. No model download is required *after* installing the APK. Initial use unpacks a chosen model to private storage; it needs substantial free space. v1.0 offers 28 English voices (including British male options); v1.1 includes three English voices plus Mandarin voices.

Weights are **not additionally trained or fine-tuned**. Voice selection, preprocessing and per-voice audio caching are software optimizations.

## Build

GitHub Actions → **Build VOX 0.4 Reader & UI** → Run workflow. The workflow downloads both pinned Kokoro archives, builds the Android APK, verifies the embedded model assets and uploads it as a downloadable artifact. This version targets 64-bit ARM Android devices, with minSdk 26.

## Known limitations and testing

- A successful GitHub build verifies compilation and APK structure, **not** uninterrupted speech on your specific device.
- PDF OCR, tables, mathematical formula voicing, synchronized word highlighting, reflowable embedded images and footnote navigation are not yet implemented.
- Some EPUBs with DRM, malformed XML or unusual encrypted/remote resources may not be readable.
- First Kokoro setup can require hundreds of MB of storage and CPU time.
- Continuous chapter narration currently prepares uncached chunks as needed and may pause between synthesis chunks. Playback is not yet guaranteed gapless.
- Debug builds may be signed with different keys from older releases. Back up important local books before uninstalling an earlier APK.

## Third-party packages

- Sherpa-ONNX / Kokoro: https://github.com/k2-fsa/sherpa-onnx
- HTML parser: https://jsoup.org/
- PDFBox Android: https://github.com/TomRoush/PdfBox-Android

Only process books you own or have permission to access. The app does not include proprietary ElevenReader services or copyrighted books.

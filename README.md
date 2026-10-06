# PDF Reader

A minimal Android app that reads the text of a PDF out loud, fully offline.

- **PDF text:** [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- **Speech:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) running a [Piper](https://github.com/rhasspy/piper) voice (`en_US-lessac-medium`)

## Getting the app

Every push to `main` builds the app on GitHub. When the build finishes, open
**Releases → Latest build** and download `pdf-reader.apk` on your phone.
Android will ask you to allow installs from your browser the first time.

Builds are signed with the same key each time, so a new APK installs over the old one.

## Using it

Open PDF → Play. Pause, Stop and a speed slider are the only other controls.
You can also choose this app from "Open with" on a PDF in your file manager.

## Known limits of this first version

- Scanned PDFs (pictures of pages) have no text to read; that needs OCR.
- Playback is not a background service, so Android may stop it some time after
  you leave the app or the screen turns off.
- It always starts from the beginning of the document.
- 64-bit ARM phones only (nearly every phone from the last several years).

## Building in Android Studio later

The speech engine and voice are not stored in this repo; the workflow in
`.github/workflows/build.yml` downloads them. To build locally, run the same
two downloads so that these exist:

- `app/libs/sherpa-onnx.aar`
- `app/src/main/assets/voice/` (containing `en_US-lessac-medium.onnx`, `tokens.txt`, `espeak-ng-data/`)

All of the app code is in `app/src/main/java/com/martin/pdfreader/MainActivity.kt`.

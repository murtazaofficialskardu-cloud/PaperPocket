# Validation status

Recorded on 2026-10-10 for PaperPocket 1.0.0 (version code 1). The signed release
APK and AAB are built and verified. Android 8/API 26 executed all 21 native
integration tests successfully. The signed release APK installed and launched on the local API 26 emulator,
and PaperPocket Home rendered. The emulator then displayed a System UI
non-response dialog, which blocked navigation capture. That diagnostic image is
excluded from the store kit; obtain clean screenshots on a physical phone.

Physical-device scanner/camera and external-provider acceptance remain to be
completed using [MANUAL_TESTS.md](MANUAL_TESTS.md). These emulator results do not
claim that a real sheet has been scanned or that Google Play has approved the app.
GitHub Actions results are recorded below.

## Native build and runtime checks

| Check | Result |
| --- | --- |
| Android Java/resource compilation | Passed against Android SDK 36, JDK 17, Gradle 8.13, AGP 8.13.2. |
| `assembleDebug`, `assembleRelease`, `bundleRelease`, `assembleDebugAndroidTest` | Passed; APKs and signed release bundle generated. |
| `testDebugUnitTest` | 8 passed, 0 failures/errors/skipped. |
| `lintDebug`, `lintRelease` | 0 errors; 31 debug and 31 release warnings. |
| Android 8/API 26 instrumentation | 21 passed, 0 failures/errors/skipped, using GitHub Actions API 26 emulator through `connectedDebugAndroidTest`. |
| Integration scope | Android decoding/EXIF/transparency, multi-page PDF conversion and native rendering, library persistence/deletion, corrupt/truncated files, orphan recovery, stable naming, and transport-failure preservation. |
| Signed release installation/UI | APK installation and Home launch passed on API 26; further UI capture was blocked by a System UI non-response dialog in the software-only emulator. |
| APK signing | `apksigner verify` passed; v2 signature, RSA 4096-bit upload key. |
| AAB signing | `jarsigner -verify` passed. |
| AAB structure | Official bundletool 1.18.3 `validate` passed. |
| Native libraries | No `.so` libraries in the assembled APK. |
| Package metadata | `io.github.murtazaofficialskardu_cloud.paperpocket`, 1.0.0 / 1, minimum API 26, target API 36. |

The Android 8/8.1 PDF verification path uses a private, isolated process for each
file. This avoids the platform's native PDF error state affecting later healthy
files. Viewer parsing uses a separate private process. A transport/startup failure
preserves files and metadata; if a verifier never connects, restart the app before
retrying. No PDF contents are sent to a server by these checks.

The final manifest requests `INTERNET`, `ACCESS_NETWORK_STATE`, and the app-scoped
signature permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It requests no
direct camera, broad photo/storage, or all-files permission. Viewer, crop, probe
service, and FileProvider are private; document backup/transfer is disabled.

Native build reports are in `app/build/reports/`. Unit-test XML is in
`app/build/test-results/testDebugUnitTest/`. Local emulator logs/results and the diagnostic Home capture are in the
device-validation record. Clean store screenshots must be captured on a phone.

## Portable PDF writer

The actual `PdfGeometry` and `StreamingImagePdf` classes passed 52 checks under a
24 MB Java heap, including a JPEG larger than 32 MB, streaming, cancellation,
size limits, output/trailer failure handling, geometry, and safe display names.
An independent PyMuPDF renderer opened and checked all 103 pages across the
three-page and 100-page exports without repair. Cross-reference offsets and
stream lengths were checked separately.

Reproduce with Java 17, PyMuPDF, and Pillow:

```sh
python3 tools/validate_pdf.py
```

Reproduce Android checks with the SDK/device installed:

```sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:connectedDebugAndroidTest
```

## Produced build artifacts

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 4,379,581 | `15441efc89e9de28ad02ab5c32981980250ced1ea23aa2c41aa3cadf202e5d06` |
| `app/build/outputs/apk/release/app-release.apk` | 3,180,469 | `69159f55dcc733cc60d888480a1cc9bd8b00f3adc36425266c44ef2062c19fe9` |
| `app/build/outputs/bundle/release/app-release.aab` | 3,001,219 | `52e286d407616b66889f38162294353ba3e8d6a53f856591c11dc36013b590f9` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 386,126 | `b989b51d7ef67913b70b5da347c4364b1aa2a2a45fdae5d0f22e0cf0e1a01150` |

The delivery's `release-manifest.json` records hashes of the named APK, AAB,
source ZIP, and store kit. Signing keys/passwords stay outside the repository and
public release. The private upload-key backup is supplied separately to the owner.

## Remaining publisher checks

- Test real-sheet scanning, scanner download/cancellation, and camera fallback on a supported phone.
- Complete the manual provider, sharing/export, crop/filter, lifecycle, low-resource, accessibility, and API 36 UI checks.
- Test the uploaded AAB through Play's internal track and review its pre-launch report.
- Complete Play Console account verification, listing, app-content/Data safety declarations, applicable closed testing, and Play review.
- Windows bootstrap execution has not been tested in this Linux workspace.

The app is delivered as installable software and a signed upload bundle; these
results do not establish production Play Store acceptance.

## GitHub Actions

GitHub Actions passed for commit `5f75ceb`: [build and Android device tests](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38016471189).

- APK/AAB builds, unit tests, and debug/release lint passed.
- Android 8.0 / API 26: **21 tests passed**, none skipped or failed.
- Android 16 / API 36: **20 tests passed**, one expected skip for the Android 8-specific verifier test; none failed.

Device test counts were verified from the uploaded JUnit XML reports.

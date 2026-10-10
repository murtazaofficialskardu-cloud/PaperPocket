# Validation status

Recorded on 2026-10-10. The native Android SDK 36 build has succeeded. Debug and
signed release APKs, a signed release AAB, and the Android-test APK were produced.
All 8 Gradle unit tests pass; debug and release lint each have 0 errors and 31
warnings. Release artifact signatures were verified.

Android instrumentation and physical-device acceptance remain pending while
emulator/device setup is being completed. Building the test APK does not execute
the tests. Scanner, camera, import/export providers, and lifecycle behavior have
not yet passed device acceptance. The initial toolchain/network block has been
resolved for the native build.

## Checks executed in this workspace

| Check | Result | Scope |
| --- | --- | --- |
| Java 17 language grammar | Passed, 12 source files | `JavacTask.parse()` only; Android symbols and types were not resolved or compiled. Includes main, unit-test, and Android-test source. |
| Android XML syntax | Passed, 11 XML files | XML parser well-formedness only; no Android resource linking or manifest-merger validation. |
| POSIX shell syntax | Passed | `sh -n gradlew tools/bootstrap-gradle.sh`; this syntax check is separate from the native build reported below. |
| Portable PDF writer | See results below | Runs with the Java compiler and an independent PDF renderer; does not execute Android Bitmap/PdfRenderer integration. |

Reproduce the syntax checks from the project directory:

```sh
mkdir -p /tmp/pp-check
java com.sun.tools.javac.Main -d /tmp/pp-check tools/CheckJavaSyntax.java
java -cp /tmp/pp-check CheckJavaSyntax app/src
sh -n gradlew tools/bootstrap-gradle.sh
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
files = list(Path('app/src').rglob('*.xml'))
for path in files:
    ET.parse(path)
print(f'XML syntax valid: {len(files)} files.')
PY
```

## Portable PDF writer results

Passed using the actual `PdfGeometry` and `StreamingImagePdf` Java classes. The
portable harness runs 52 checks under a 24 MB Java heap, including a valid JPEG
larger than 32 MB to verify image bytes are streamed instead of buffered in full.
It also checks empty-document rejection, finished/failed writer behavior,
cancellation, configured size limits, injected output/trailer failures, page
geometry, and safe display names.

An independent PyMuPDF renderer opened the three-page and 100-page exports
without repair and verified all 103 pages. Checks cover A4 portrait, Letter
landscape, automatic page dimensions, RGB JPEG image resolution, unchanged
red-top/blue-bottom orientation, image placement, and white margins. Separate
byte-level checks verify every cross-reference offset and binary stream length.

Reproduce from the project directory with Java 17+ (including `jdk.compiler`),
PyMuPDF, and Pillow installed:

```sh
python3 tools/validate_pdf.py
```

Add `--output-directory /path/to/results` to preserve the generated PDFs and
render previews. These checks compile and execute the pure Java writer; they do
not execute Android image decoding, bitmap processing, `PdfRenderer`, storage,
or device flows. `PdfFlowTest` supplies the real Android decoding, transparency,
conversion/rendering, and library persistence/deletion check; it remains
unexecuted until an Android device or emulator is available.

## Native build and signing results

| Check | Result |
| --- | --- |
| Java compilation and Android resource packaging | Passed against Android SDK 36. |
| `:app:assembleDebug` | Passed; debug APK generated. |
| `:app:assembleRelease` | Passed; signed release APK generated. |
| `:app:bundleRelease` | Passed; signed release AAB generated. |
| `:app:assembleDebugAndroidTest` | Passed; instrumentation APK compiled and packaged, not executed. |
| `:app:testDebugUnitTest` | Passed: 8 tests, 0 failures, 0 errors, 0 skipped. |
| `:app:lintDebug` | Passed: 0 errors, 31 warnings. |
| `:app:lintRelease` | Passed: 0 errors, 31 warnings. |
| Release APK signature | `apksigner` verification passed; APK Signature Scheme v2, RSA 4096-bit key. |
| Release AAB signature | `jarsigner` verification passed: JAR verified. |
| Release package metadata | Application ID `io.github.murtazaofficialskardu_cloud.paperpocket`, version 1.0.0 / code 1, minimum API 26, target API 36. |

The 31 warnings per variant comprise 17 `SetTextI18n`, 3
`ClickableViewAccessibility`, 3 `DrawAllocation`, 3 `UnusedResources`, 2
`MonochromeLauncherIcon`, and one each of `UnusedAttribute`,
`AndroidGradlePluginVersion`, and `ObsoleteSdkInt`. They remain warnings; they
were not silently reclassified as errors or described as a warning-free run.

The inspected release merged manifest requests `INTERNET`,
`ACCESS_NETWORK_STATE`, and the app-scoped signature permission
`io.github.murtazaofficialskardu_cloud.paperpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
It does not request direct camera, broad photo/media, broad storage, or all-files
access permissions.

Reports are in `app/build/reports/`, and the unit-test XML is
`app/build/test-results/testDebugUnitTest/TEST-com.paperpocket.app.PdfGeometryTest.xml`.

### Produced artifacts

| File | Size in bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 4,379,581 | `786874a3b68157bee773772deb6a920af05c5c9d755cff9f7b97570e7123bc08` |
| `app/build/outputs/apk/release/app-release.apk` | 3,176,541 | `bd778a54692346db40225387df328be6c2456907d7bc7aa90d8dffeae9babfd1` |
| `app/build/outputs/bundle/release/app-release.aab` | 2,997,257 | `9a3b324c254d92140560106a27cb5618bda1845981a47d088d18cb403955faa6` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 370,305 | `db7da13f0a196d137bc27b8347920be399ffe3cdd1bd9675805a315c099a5854` |

Signing keys and passwords are kept outside the repository. A signed AAB still
needs the publisher's Play Console submission and Play App Signing setup; it is
not evidence of store approval.

## Pending runtime and external checks

| Check | Status |
| --- | --- |
| `:app:connectedDebugAndroidTest` | Not yet executed; emulator/device setup is in progress. |
| Scanner, camera, import, crop, view, share, export, lifecycle acceptance | Not run; follow `MANUAL_TESTS.md` on physical devices. |
| Windows PowerShell bootstrap execution | Not run in this Linux workspace. |
| GitHub Actions workflow results | Workflow source is in the public repository; remote run success has not been verified in this record. |
| Google Play submission and approval | Not completed by the native build. |

Build instructions are in `README.md`. Native compilation, passing unit tests,
and signature verification do not establish that all device flows have passed
acceptance. Follow `MANUAL_TESTS.md` before production release.

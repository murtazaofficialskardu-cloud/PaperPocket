# Validation status

Recorded on 2026-10-09. This source bundle **has not been compiled against the
Android SDK, and no APK is included**. The Android SDK and Gradle were unavailable
in the execution workspace; downloads could not connect to its configured
network proxy. External download retries were stopped. The source must pass a
native Android build and device acceptance checks before release.

## Checks executed in this workspace

| Check | Result | Scope |
| --- | --- | --- |
| Java 17 language grammar | Passed, 12 source files | `JavacTask.parse()` only; Android symbols and types were not resolved or compiled. Includes main, unit-test, and Android-test source. |
| Android XML syntax | Passed, 11 XML files | XML parser well-formedness only; no Android resource linking or manifest-merger validation. |
| POSIX shell syntax | Passed | `sh -n gradlew tools/bootstrap-gradle.sh`; download/build scripts were not executed. |
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

## Pending native checks

| Check | Status |
| --- | --- |
| `:app:assembleDebug` | Not run; no Android SDK/Gradle available. |
| `:app:testDebugUnitTest` | Not run through Gradle. |
| `:app:lintDebug` | Not run. |
| `:app:connectedDebugAndroidTest` | Not run; requires an Android device or emulator. |
| Scanner, camera, import, crop, view, share, export, lifecycle acceptance | Not run; follow `MANUAL_TESTS.md` on physical devices. |
| Windows PowerShell bootstrap execution | Not run in this Linux workspace. |
| POSIX Gradle bootstrap/dependency downloads | Not run successfully; network connectivity blocked toolchain setup. |
| GitHub Actions workflow | Source supplied only; no workflow was triggered. |

Build instructions are in `README.md`. Do not treat language parsing or XML
well-formedness as proof that Android compilation, runtime behavior, or all
requested features have passed acceptance tests.

# PaperPocket 1.2.0 beta validation

Recorded 14 October 2026. This is a free GitHub beta; store publication and
monetization are deferred. Package `io.github.murtazaofficialskardu_cloud.paperpocket`,
version code 3, minimum API 26, target API 36. The release APK retains the original
publisher certificate, so earlier GitHub installations can update in place.

## Executed checks

App source tested: `22ad00579212cd74b16c56b3ed8b8f8e27916eaa`.
[GitHub Actions run and recorded rerun](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38387291824). Counts were read from
uploaded JUnit XML, with report SHA-256 checks.

| Check | Result |
| --- | --- |
| SDK 36 debug/release APK and signed AAB | Built successfully |
| Unit tests | 8 passed |
| Debug/release lint | Each: 0 errors, 32 warnings |
| Android 8 / API 26 / x86_64 | 51 passed, none skipped or failed |
| Android 16 / API 36 / x86_64 / 4 KB | 50 passed, 1 expected legacy-only skip, none failed |
| Android 16 / API 36 / x86_64 / 16 KB | 50 passed, 1 expected legacy-only skip, none failed |
| Native 16 KB test environment | Observed PAGE_SIZE 16384; linker compatibility false; package compatibility disabled true |
| APK/AAB signatures | Original RSA 4096-bit certificate; APK v2 and AAB JAR signatures verified |
| Bundle structure and packaging | Official bundletool 1.18.3 validates; PAGE_ALIGNMENT_16K; APK zipalign -P 16 passes |

The skipped test is `unavailableLegacyVerifierPreservesHealthyLibraryMetadata`,
which applies only to Android 8. The API 26 image lacks the shell getconf command;
its expected 4 KB image page size was not directly queried.

Native tests cover image decoding/orientation/transparency, PDF geometry and
rendering, compression/profile tradeoffs, malformed-input recovery, and library
persistence. New tests cover cancellation before a queued conversion starts and
after page progress, exact draft/source preservation, and no partial library entry.

Ten Trash tests cover retained bytes/names/metadata, Undo/Restore across store
reconstruction, interrupted moves/restores, index loss, write failures, unsafe IDs,
collisions, and targeted permanent deletion. Seven merge/extraction tests verify
order, dimensions, JPEG payloads, invalid/unsupported input, cancellation, and
original-file preservation. Five OCR tests verify real printed English/digits,
page separators, exact original hashes, malformed recovery, cancellation/retry,
explicit page/text limits, blank output, and ten consecutive isolated worker
retirements. The retirement regression exposed and then verified an Android 8
binding-cleanup fix.

Navigation tests check centered icons/labels and usable tabs. Additional actual
Android display/font settings exercise a 360 dp phone at 2× font scale and a
600 dp tablet width at 1.3× scale; navigation and control text fit checks pass.
Three real native emulator PNGs are supplied in `store-assets/beta-ui/` and the
store kit. They show empty Home, Pages, and My PDFs states, not scanned document
quality or physical-phone certification.

Portable merge/extraction validation passed 57 checks under a 24 MiB heap;
independent MuPDF checks verify JPEG hashes, unchanged rendering/dimensions,
ordered pages, and a 500-page output that opens without repair. Reproduce with
`python3 tools/validate_pdf_operations.py --output-directory /tmp/pp-operations-check`.
The existing writer validation records 61 checks and 103 independently rendered pages.

## SDK and warning evidence

The final APK requests INTERNET, ACCESS_NETWORK_STATE, and an AndroidX private
signature permission. It does not request camera, broad storage/photos, SMS,
accessibility, notification listener, location, contacts, microphone, advertising
ID, or install-packages access. Private helper services/provider/activities are
not exported; local document backup remains disabled.

Bundled Latin OCR adds native libraries and model assets. The universal APK is
45,847,711 bytes (about 44 MiB); the AAB is about 21 MB and can deliver the device's
architecture through Play later. Both 64-bit native libraries have 16 KB LOAD
alignment. The stricter RELRO endpoint arithmetic in current Android guidance
flags the vendor binaries; their next writable segment is separated from the
rounded RELRO range. Actual x86_64 16 KB recognition tests pass with compatibility
disabled. This is tested emulator evidence, not a claim about every ARM64 phone
or a future Play validator; verify the current SDK and ARM64 devices before launch.

Earlier CI attempts included an Android 8 shell-command setup error and a 16 KB
emulator/ADB failure before app installation. Diagnostics showed device offline
while requesting adb root; that failed job was rerun. A separate actual Android 8
OCR worker restart defect was fixed in the tested source above. Final successful
results are recorded separately from those failed attempts.

The owner's screenshot says Play Protect has not seen an app from this developer
before. It does not name detected malware. Local tests/signatures do not clear
Google's verdict. No Google review was submitted or clearance claimed; the
screenshot predates 1.2.0 and its APK version was not recorded. See
[TESTING_AND_SECURITY.md](TESTING_AND_SECURITY.md) and the owner-review draft.

## Remaining acceptance

Check 1.2.0 on real phones: scanner/component downloads and camera fallback,
cloud/local picker providers, Share/Save TXT and PDF destinations, low resources,
large names/drafts, accessibility, process recreation, and update retention.
Current long jobs cancel when their activity is destroyed before final saving;
durable background completion/resumption is future work. Compression creates an
image copy and keeps the original. Merge/extraction supports known PaperPocket
image PDFs; OCR returns separate Latin text, with no Urdu or searchable PDF layer.

No Play Store, iOS app, or hosted Site has been published. Artifact hashes and
exact CI records are in the versioned `downloads/` files. Signing secrets and
private backups remain outside the public repository and archives.

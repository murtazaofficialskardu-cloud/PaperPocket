# PaperPocket 1.4.0 beta validation

Free GitHub beta, version code 5; package
`io.github.murtazaofficialskardu_cloud.paperpocket`, minimum API 26, target API 36.
Version 1.4 starts a **new distribution signing identity**. It requires a fresh
install from 1.3 or earlier; export wanted PDFs before uninstalling, because
private PDFs, drafts, folders and Favorites are removed. No automatic migration
or external PDF import is supplied. Future 1.4-key releases can update 1.4 normally.

## Executed app/source checks

Tested app/test source: `26480f45ff9641063846ab2b79d4aa315fbd708b`.
[GitHub Actions run](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/41073341442).
[Verified JUnit/artifact record](validation/1.4.0/final-ci-results.json).
All four report ZIP SHA-256 values were checked against GitHub metadata.

| Check | Recorded result |
| --- | --- |
| SDK 36 debug/release builds, unsigned bundle and test APK | Passed before release signing |
| Unit tests | 11 passed |
| Debug lint | 0 errors, 37 warnings |
| Release lint | 0 errors, 37 warnings |
| Android 8 / API 26 / x86_64 | 74 passed, none skipped or failed |
| Android 16 / API 36 / x86_64 / 4 KB | 73 passed, 1 expected skip, none failed |
| Android 16 / API 36 / x86_64 / 16 KB | 73 passed, 1 expected skip, none failed |
| Strict 16 KB environment | PAGE_SIZE 16384; linker compatibility false; package compatibility disabled true |

The skipped test is `unavailableLegacyVerifierPreservesHealthyLibraryMetadata`,
which applies only to Android 8. That image lacks shell getconf, so its expected
4 KB page size was not directly queried. Lint warnings remain in the reports;
passing lint is not a claim of zero warnings.

Four added compression tests verify candidate restore/discard/expiry, unchanged
original bytes, exact reviewed bytes saved, refusal of a larger result, unsafe
IDs/symlinks, and the real Main → private viewer → explicit Save flow. The UI test
waits for a rendered page, not just a page counter. No library copy appears before
Save; an unsaved preview has no share/export controls.

Existing suites continue to cover image decoding/orientation, rendering and PDF
geometry, malformed recovery, compression, background creation/cancellation and
source lifetime, folders/Favorites, Trash/Restore, merge/extraction and bundled
Latin OCR. Navigation checks exercise 360 dp with 2× fonts and 600 dp with 1.3×
fonts. Three genuine native screenshots per device were checked as PNGs at
1080 × 1920. These emulator results do not certify every physical phone or the
final signed APK's installation behavior.

## Distribution artifact record

The final distribution certificate, APK/AAB hashes and sizes, source provenance,
and any completed signature/alignment checks are recorded in the versioned
[release manifest](downloads/release-manifest-1.4.0.json) and
[checksums](downloads/SHA256SUMS-1.4.0.txt). The source CI build above uses an
unsigned release bundle; its success alone does not verify a signed delivery.

Before publication, verify the new APK/AAB signatures, package/version/minimum
and target SDK, ZIP/bundle alignment and exact tested app inputs. Keep keys,
passwords and private recovery archives outside Git and public downloads.

The epoch-2 signing backup is verified in the owner's private Google Drive. Its
permissions contain only the user owner, with no public/domain sharing. An
authenticated download into a fresh restore directory matched the ZIP checksum
and new certificate. The restored key/passwords signed a JAR, and jarsigner
verified that signature. This proves recovery of the private signing material;
it is separate from final APK/AAB signature and install checks.

The generated epoch-2 certificate SHA-256 is
`9c013d3bdf3a96b66cddf70d1c5d55361d157d5f13ab2e9308ec4967330c2f30`.
The old 1.3 certificate is not the expected signer of the 1.4 distribution.

The unchanged OCR SDK includes 64-bit native libraries with 16 KB LOAD alignment.
Strict RELRO endpoint arithmetic flags those vendor binaries; their next writable
segment is separated from the rounded range. Actual x86_64 16 KB OCR tests pass
with compatibility disabled. ARM64 phone and future Play validation remain
separate launch checks.

## Permissions and privacy

The app requests INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, POST_NOTIFICATIONS and the AndroidX
private signature permission. Notification permission is optional on Android 13+.
No camera, broad photo/storage, microphone, location, contacts, SMS,
accessibility, notification-listener, advertising ID or install-packages access
is requested. Helper components are private; document backup remains disabled.

Compression candidates stay in private cache. Save or Discard removes the
working copy; abandoned candidates older than 24 hours are cleaned at the next
app start or preparation, and Android may evict cache earlier. Saved activity
state can restore a review only while its cache file exists. Original PDFs are
not overwritten. The public website has separate privacy information.

Play Protect warning work remains paused. The earlier screenshot reports an
unfamiliar developer, not named malware. Changing the signing identity is a beta
recovery decision, not a Google warning fix. No review, clearance or warning
removal is claimed.

## Remaining phone feedback and launch work

Check actual scan/camera quality, providers, sharing, low resources, screen-off
creation, enlarged compression-dialog text and preview readability on phones.
The 1.3 → 1.4 fresh-install reset is intentional; no data-preserving in-place
update is promised across those signing identities. Test future same-key updates
separately once another 1.4-key build exists.

PDF creation can continue minimized; force-stop, reboot or process termination
does not automatically resume. Compression, import, merge/extraction and OCR
remain activity-bound. OCR returns separate Latin text, without an Urdu,
handwriting or searchable-PDF promise. Merge/extraction supports known
PaperPocket image PDFs; arbitrary external PDFs are not imported.

The download/support website is public at https://paperpocket.sm-ali.chatgpt.site.
Play Store, native iOS, browser PDF tools, ads, billing and paywalls remain outside
this release. Earlier signed artifacts and tags are preserved as history.

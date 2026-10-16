# PaperPocket 1.3.0 beta validation

Free GitHub beta, version code 4. Package
`io.github.murtazaofficialskardu_cloud.paperpocket`, minimum API 26, target API 36.
Play publication and monetization remain deferred. The APK retains the original
publisher certificate and updates earlier GitHub installations in place.

## Executed checks

App source tested: `12e3554b6c07a61f53287953cbe8904cda4838fe`.
[GitHub Actions evidence](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38829729538). Uploaded JUnit reports and artifact
SHA-256 digests were checked before recording these results.

| Check | Result |
| --- | --- |
| SDK 36 debug/release APK and signed AAB | Built successfully |
| Unit tests | 11 passed |
| Debug lint | 0 errors, 37 warnings |
| Release lint | 0 errors, 37 warnings |
| api-26 / x86_64 | 70 passed, 0 skipped, none failed |
| api-36 / x86_64 | 69 passed, 1 skipped, none failed |
| api-36-16kb / x86_64 | 69 passed, 1 skipped, none failed |
| 16 KB runtime | PAGE_SIZE 16384; linker compatibility false; package compatibility disabled true |
| APK/AAB signatures | Original RSA 4096-bit certificate; APK v2 and AAB JAR signatures verified |
| Bundle and ZIP alignment | bundletool 1.18.3 validates; PAGE_ALIGNMENT_16K; APK zipalign -P 16 passes |

The expected skip is `unavailableLegacyVerifierPreservesHealthyLibraryMetadata`,
which applies only to Android 8. The API 26 image lacks shell getconf, so its
expected 4 KB page size was not directly queried. Lint warnings are retained in
the reports; a passing lint task does not mean the warning count is zero.

The earlier [1.3 CI attempt](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38772606627)
caught a private-storage alias rejection. The final source accepts normal Android
directory aliases while retaining canonical containment and symlink-leaf checks.
API 26 notification-state and JPEG/PDF color-rounding assumptions were also
corrected in the tests; all platform cases remain enabled. Failed and final
successful evidence are kept separately.
The [next attempt](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38774137629)
confirmed Android SELinux blocks hard links. Final snapshots use bounded read
handles and cancellable worker copies; no image bytes are copied on the UI thread.
The unlink regression removes the draft file before the service can copy it.

Seven new background-job tests exercise named normal and ID-card creation, an
activity closing and its draft image being unlinked, queued and progress-time
cancellation, persisted interrupted-job recovery, notification denial, stale
notification/start actions, and a new job starting during completion cleanup.
They verify original bytes, output counts, snapshot cleanup, and source-boundary/symlink rejection. These are real
foreground-service tests, including Android 8 and modern foreground restrictions.

Nine folder tests exercise legacy-index migration, durable Favorites and folder
membership, rename/trash/restore, interrupted restore recovery, folder deletion
without PDF deletion, invalid names/IDs, failed writes, and corrupt registry
handling that preserves document metadata. Four ID-card integration tests verify
one A4 page, two rendered sides, filters/rotation/white padding, cancellation,
source hashes, invalid inputs, and cleanup. Three geometry unit tests verify
physical card frames and aspect-preserving placement.

Existing suites continue to cover image decoding/orientation/transparency,
PDF geometry/rendering and compression, malformed input, library persistence,
Trash/Undo/Restore, ordered lossless merge and page extraction, and bundled
printed Latin OCR with cancellation and repeated worker retirement.
Navigation checks exercise actual 360 dp / 2× font and 600 dp / 1.3× font settings.
Three native emulator PNGs in `store-assets/beta-ui-1.3/` and the store kit show
Home, Pages, and My PDFs. They are not physical-phone scan-quality evidence.

## Permissions and SDK evidence

The merged APK requests INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, POST_NOTIFICATIONS, and the AndroidX
private signature permission. Notification permission is optional on Android 13+.
The private local-processing service uses a bounded CPU wake lock; denial of
notifications does not prevent creating a PDF. No camera, broad storage/photos,
microphone, location, contacts, SMS, accessibility, notification-listener,
advertising ID, or install-packages access is requested. Helper components are
private and document backup remains disabled.

Universal signed APK: 45,866,079 bytes (43.7 MiB).
Signed AAB: 21,400,446 bytes. The APK bundles the Latin OCR model and supported
CPU architectures; Play can deliver the matching architecture later.
The unchanged OCR vendor libraries have 16 KB LOAD alignment. Strict RELRO
endpoint arithmetic flags those SDK binaries; their next writable segment is
separated from the rounded range. Actual x86_64 16 KB OCR tests pass with
compatibility disabled. ARM64 phone and future Play validation remain separate
launch checks.

The supplied Play Protect screenshot reports an unfamiliar developer. It does
not name detected malware. Warning work is paused at the owner's request;
no new Google appeal, clearance, or removal guarantee is claimed.

## Remaining acceptance

Review this version on real phones: update retention, document-scanner downloads,
camera fallback, local/cloud picker providers, screen-off/minimized creation,
notification allow/deny, low storage and memory, PDF/TXT sharing, long names,
ID-card crop/print quality, folder/Favorite usability, and accessibility.
Creation can continue after the activity closes; force-stop, reboot, or process
termination does not automatically resume a job. A stopped job keeps its draft.
Compression, merge/extraction, and OCR still use activity-bound cancellable jobs.
The save barrier finishes atomically and then Cancel is unavailable.

ID mode places two photos in standard ID-1 frames on one A4 page. Crop each photo
to the card first; no automatic perspective correction or print-scale guarantee
is implied. Merge/extraction supports known PaperPocket image PDFs. OCR exports
separate Latin text, without Urdu/handwriting promises or a searchable PDF layer.

No Play Store, iOS app, hosted Site, ads, billing, or paywall has been published.
Versioned downloads contain exact hashes and CI records. Signing secrets and
private backups remain outside the public repository and archives.

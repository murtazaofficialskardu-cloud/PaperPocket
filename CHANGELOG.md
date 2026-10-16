# Changes

## 1.3.0 — free beta

- Normal and ID-card PDF creation now runs in a foreground service while the
  app is minimized, with photo progress, Cancel before final saving, and a
  saved completion/result banner after reopening. One conversion runs at a time.
- Optional Android 13+ notifications show conversion progress and completion.
  Declining notification permission still allows PDF creation. Notifications
  avoid document contents and names on the lock screen.
- Private job snapshots protect the selected images while a conversion runs.
  Cancellation/failure keeps the draft; completion clears only the matching
  unchanged draft. Force-stop/reboot/process termination do not auto-resume a job.
- Added folders and Favorites in My PDFs, with library filters, Move to folder,
  and folder rename/delete. Deleting a folder keeps its PDFs in All PDFs.
  Trash/Restore retains Favorite/folder metadata when that folder still exists.
- Added Create ID-card PDF: exactly two photos, front then back, fitted without
  stretching into standard card-size frames on one portrait A4 page. Crop each
  source close to its card edges before creating the PDF.
- Home keeps its existing main actions; new document tools live in Pages and
  My PDFs. Background status scrolls with the body so navigation stays usable.
- Added background-job, ID-card output/layout, and folder persistence/recovery
  coverage. Exact executed results are recorded in VALIDATION.md.
- Same signing identity and application ID, version code 4. Still free, without
  ads, purchases, or a paywall. Play publication and monetization stay deferred;
  installation-warning investigation is paused at the owner's request.

## 1.2.0 — beta, 14 October 2026

- Determinate page progress and Cancel for imports, conversion, compression,
  merging, extraction, and OCR. Cancellation retains drafts/source PDFs and
  cleans incomplete copies. Cancel is disabled once the final save begins.
- Persistent PDF Trash with Undo, Restore, and confirmed permanent deletion
  or Empty Trash. No automatic expiry; original bytes and document metadata remain.
- Lossless merge with chosen document ordering, plus selected-page extraction
  with comma/range input. Supports the app's known image-PDF format, preserves
  embedded JPEG bytes/page geometry, and keeps source PDFs unchanged.
- Optional bundled on-device Latin/English text extraction, up to 50 pages,
  with Copy, Share TXT, and Save TXT. No initial OCR model download; no Urdu or
  searchable PDF claim. PDF parsing uses a fresh isolated worker process.
- Adaptive library search height and Android UI checks for narrow phones with
  2× fonts and tablet width with enlarged fonts.
- Added cancellation, crash/restart trash recovery, lossless document-operation,
  and real OCR fixtures. New OCR SDK data handling is disclosed in all policies.
- Recorded the supplied unknown-developer Play Protect warning reason and the
  normal scan/review route. No Google clearance or warning bypass is claimed.
- Same signing identity and application ID, version code 3. Free beta with no
  ads/billing/paywall; Play Store and monetization remain deferred.

## 1.1.0 — beta, 13 October 2026

- Centered navigation icons above Home, Pages, and My PDFs labels, equal touch
  targets, and adaptive control heights for larger fonts.
- Simplified action labels and creation options; paper/margins are under More options.
- Balanced is the new default: 200 DPI target / JPEG 82. Compact uses 150 DPI / 68;
  High uses 300 DPI / 90. Source detail and content determine the actual file size.
- My PDFs now offers Reduce file size. It retains the original, saves only a
  smaller copy, and shows actual before/after sizes and space saved.
- Recompression preserves page count/proportions and creates page images; text
  selection, links, and interactive PDF features are not retained in the copy.
- Added native compression, corrupted-input recovery, and navigation layout tests.
- Same publisher signing certificate/application ID, with version code 2.

Play Protect classification remains an external review question; the update does
not claim Google's approval. Public store launch is postponed while phone
feedback is collected. iOS and Sites remain proposed follow-up work.

## 1.0.0 — 10 October 2026

Initial Android image import/scanning, page editing, PDF conversion, private
library, viewer, sharing/export, draft recovery, and Android 8 PDF recovery support.

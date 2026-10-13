# PaperPocket acceptance tests

Verification status: this device checklist has not been run. Native debug/release APKs and the signed release AAB have been built, all 8 Gradle unit tests pass, and debug/release lint each report 0 errors and 31 warnings. Release signatures were verified. See `VALIDATION.md` for artifact hashes and exact results. Device instrumentation and acceptance remain pending; those build results do not establish that scanning, camera handoff, sharing, or document providers work on a device.

Record the APK version, device model, Android version, test date, and results before describing a release as tested. Update this status when an APK is built and the checks below are executed.

## Build and install

Use the build setup in `README.md`. Once the Android SDK and dependencies are available, run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.paperpocket.app/.MainActivity
```

An APK must exist and install successfully before starting the device checklist. Unit tests exercise PDF layout and naming rules; the following tests cover Android integration and visual output.

## Devices and fixtures

At minimum, test an Android 8/API 26 device or emulator and an Android 15/API 35 device. Test the scanner on a physical device with supported Google Play services; use a device without supported Play services to check the camera fallback. Include both gesture navigation and three-button navigation. If possible, add a small phone, a tablet, and a device with limited memory.

Prepare these inputs with recognizable page numbers and orientation marks:

- Three JPEG photos of a printed page, receipt, and color photograph.
- A portrait camera JPEG with EXIF rotation, a landscape JPEG, a square PNG, a transparent PNG, and a WebP image.
- A high-resolution photograph containing fine printed text, a low-resolution photograph, a dark scan, and an almost blank page.
- A skewed photograph of a rectangular sheet with all four corners visible.
- An invalid image file and an image larger than the 80 MB import limit.
- Enough numbered images to test the 100-page workspace limit.
- For viewer error tests on a disposable debug installation: a corrupt PDF, an encrypted PDF, a zero-byte PDF, and a PDF containing many pages.

Keep one known good PDF export for comparisons. Use an independent PDF reader to verify exports and a file manager or document provider to inspect saved copies.

## First launch and layout

- [ ] Launch a clean installation. Home, Workspace, and My PDFs show useful empty states. Import images and Scan document respond, and no storage or camera permission is requested by PaperPocket.
- [ ] Navigate between the three tabs. Use system Back from Workspace or My PDFs; it returns to Home. Back from Home exits normally.
- [ ] On API 35, inspect status-bar, camera-cutout, and navigation-bar areas. Header and bottom tabs remain visible and tappable. Repeat in landscape and with three-button navigation.
- [ ] Test 320 dp screen width and large font/display settings. Buttons, page tools, names, and dialog actions remain usable. Scroll to the final workspace page and Create PDF button.
- [ ] With TalkBack, verify import/scan cards, navigation, page move/remove controls, crop controls, document actions, and viewer navigation have understandable names. Focus does not become trapped after a dialog closes.

## Image import and workspace

- [ ] Import one JPEG through the Android document picker. Workspace shows one preview and page count 1. Cancel the picker afterward; the existing draft is unchanged.
- [ ] Import three numbered images with multiple selection. Each appears once, in the order supplied by the picker. The picker may choose its own selection order; rearrange in Workspace when needed.
- [ ] Import JPEG, PNG, transparent PNG, and WebP inputs. Supported images display; transparency renders on a white PDF background. Check HEIC only on devices that support decoding it.
- [ ] Import the EXIF-rotated camera image. Preview and PDF are upright without an extra manual rotation.
- [ ] Select a mix of valid and invalid files. Valid pages are kept, failed pages produce an explanation, and the app stays responsive. Unsupported files do not leave a broken page card.
- [ ] Try an image over 80 MB. It is rejected with a useful error, and the existing draft remains usable.
- [ ] Fill a draft to 100 pages. Additional import/scan actions explain the limit. Importing a selection that exceeds the remaining capacity never creates more than 100 draft pages.
- [ ] Import from a cloud-backed document provider. Copy completes before the page is reported as imported. Reopen the draft offline after a successful import; it no longer depends on the source provider.
- [ ] Check the source images in their original location. Importing, filtering, rotating, cropping, and removing draft pages never change those originals.

## Arrange, filter, and crop

- [ ] Move a middle page earlier and later. Preview positions and page numbers update, and export follows the final order. First-page Up and last-page Down are disabled.
- [ ] Rotate a page four times. It returns to its initial orientation. Reopen the app and verify the stored rotation.
- [ ] Choose Original color, Enhanced clarity, and Black & white. Preview updates and generated PDF uses the selected treatment. Cancel the filter dialog; nothing changes.
- [ ] Use a dark document and an almost blank page. Enhancement keeps text readable without turning uniform paper into excessive noise. Black & white produces grayscale output; it is not a promise of OCR or binary thresholding.
- [ ] Crop the skewed document. Move each corner onto the sheet, apply, and verify the sheet is flattened with no missing corners. The preview and final PDF use the corrected image.
- [ ] Try dragging corners into a crossed or nearly collapsed quadrilateral. Crop must prevent or reject an unusable selection rather than crash or write an invalid page.
- [ ] Reset the crop; the selection returns to the entire image. Cancel or press Back; the previous page is unchanged.
- [ ] Rotate and filter a page, then crop it. The orientation notice is understandable; the selected rotation and filter remain applied afterward.
- [ ] Rotate the device while adjusting crop corners. The selected corners survive, and Apply still produces the intended area.
- [ ] Remove one draft page. Only that page disappears. Clear draft asks for confirmation; Keep draft preserves all pages, and Clear removes the workspace while preserving generated PDFs.

## High-quality scanner and camera fallback

- [ ] On a supported Play services device, tap Scan document. Preparing feedback appears and the scanner opens. Allow its first-time component download where required.
- [ ] Scan a printed sheet under good lighting. Test automatic page-edge detection, scanner crop adjustment, cleanup, and retaking a page. Finish with multiple pages; all scanned JPEGs appear in Workspace.
- [ ] Cancel scanning. The existing draft remains intact, no blank page is added, and import/scan actions remain usable.
- [ ] Scan a receipt, a page at an angle, and a page with shadows. Inspect small text in the high-quality PDF using an independent reader. High quality targets 300 DPI; it cannot recover detail absent from the capture.
- [ ] Add scanned pages to a partly filled draft. Existing order and edits are kept; scanner capacity respects the remaining 100-page limit.
- [ ] After the scanner component has been installed, try scanning offline. Record device/provider behavior; do not assume its first launch can work offline.
- [ ] On an unsupported or unavailable scanner setup, the camera fallback explanation appears. Cancel leaves the draft unchanged; Open camera launches the installed camera app.
- [ ] Capture a photo through fallback. A full-resolution image is imported, rather than the small intent thumbnail, and it starts with Enhanced clarity. Crop, rotate, and convert it normally.
- [ ] Cancel the fallback camera or return without a valid photo. No new page is added. If no camera app is installed, the app offers an understandable error and image import remains available.

## Convert to PDF

- [ ] Convert three numbered pages. Choose a document name and High · 300 DPI. Progress advances, the PDF-ready dialog appears, the saved PDF enters My PDFs, and the completed draft clears.
- [ ] An empty document name displays a validation error and keeps the dialog open. Cancel the options dialog; the draft remains intact.
- [ ] Test A4, Letter, and Fit to image with portrait, landscape, and square sources. All content fits without stretching or unintended cropping. A4/Letter orientation follows the image; Fit to image follows its aspect ratio.
- [ ] Export with margin checked and unchecked. The margin setting visibly changes page placement. Export choices are remembered for the next conversion.
- [ ] Export the same fine-text photo at High · 300 DPI and Standard · 150 DPI. Inspect both in an independent PDF reader. High quality preserves more source detail where available; do not use file size alone as a quality test.
- [ ] Verify a low-resolution source still looks limited at high quality, and conversion does not invent detail or upscale beyond the source.
- [ ] Verify page count, final order, rotations, crops, and filters in an independent reader. The result must be a valid PDF, not an image renamed with a `.pdf` suffix.
- [ ] Use duplicate document names, Unicode names, and names containing slashes or repeated `.pdf` suffixes. Display names are normalized, and existing documents are never overwritten.
- [ ] On a disposable emulator with low free storage, force an export failure. An explanation appears, the draft remains recoverable, and no incomplete PDF appears in My PDFs.
- [ ] Convert a larger draft on a low-memory device. The app either finishes or reports a useful memory error with the draft retained; it must not leave a corrupt library entry.

## View, share, and save copies

- [ ] Open a one-page and a many-page PDF from the library and PDF-ready dialog. The viewer title and page counter are correct. Boundary Previous/Next buttons are disabled.
- [ ] Navigate repeatedly through a many-page PDF. Pages render without blank/stale content after loading, and memory use remains bounded rather than retaining every page bitmap.
- [ ] Pinch to zoom, drag in both directions, and double-tap to zoom. A tall page can be panned from top to bottom. Moving to another page resets its viewing position.
- [ ] Rotate the device while viewing a zoomed page. The same page and its zoom/pan position are restored. System Back and toolbar Back return to the library.
- [ ] In a debug test, open an unavailable, zero-byte, damaged, or password-protected PDF. The viewer gives an understandable error and Back still works. Invalid paths outside the private PDF directory are rejected.
- [ ] Share from the ready dialog, document actions, and viewer menu. A receiving app can read the complete PDF through the granted content URI, with the correct MIME type. Canceling the chooser changes no document.
- [ ] Save a copy from document actions and the viewer. Choose local Downloads and, when available, a cloud provider. Open the saved copy independently; page count and contents match the library original.
- [ ] Cancel the save picker. The library original remains unchanged and no success message appears. Try an unavailable/read-only destination; failure is explained and the original stays readable.
- [ ] Rename or delete the library original after saving a copy. The exported copy remains readable and retains its own filename.

## Library, persistence, and lifecycle

- [ ] Create several PDFs. Home shows recent documents; My PDFs shows all generated files with name, page count, size, and date. The total updates after creating or deleting a document.
- [ ] Search with different letter case, a partial name, no matches, and an empty query. Results update without losing text entry. Toggle newest-first and name sorting; order follows the selected rule.
- [ ] Rename a document. Blank input is rejected; the new display name persists after restart. Two PDFs can share a display name without replacing one another, and both still open.
- [ ] Cancel a Delete confirmation; nothing changes. Confirm Delete; only that library document disappears. Already exported or shared copies remain available.
- [ ] Import, reorder, rotate, and filter a draft; close and reopen the app. The same pages and edits return. Generated PDFs and renamed titles also survive restart.
- [ ] Rotate on Home, Workspace, My PDFs, and during import/conversion/scanner preparation. There are no leaked windows, duplicate operations, lost draft pages, or controls covered by system bars.
- [ ] Background the app during import or conversion, then return. A completed operation is reflected once, and a still-running operation does not allow conflicting changes.
- [ ] Enable Android developer option Don't keep activities. Launch and return from image picker, scanner, fallback camera, crop, share chooser, and save picker. Results attach to the restored draft exactly once, and the page limit is still enforced.
- [ ] On a disposable installation, kill the process during conversion and relaunch. The draft remains available, and the library lists only complete PDFs. Automatic resumption is not an implemented guarantee.
- [ ] Scroll a 100-page workspace and a library containing many documents, then navigate between tabs repeatedly. Previews stay associated with their pages, input remains responsive, and memory does not increase without bound.
- [ ] Clear app data or uninstall on a disposable installation. Private drafts and library PDFs are removed; previously exported copies outside app storage remain available.

## Permission and storage checks

- [ ] Inspect the merged manifest. PaperPocket requests no `CAMERA`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, or `MANAGE_EXTERNAL_STORAGE` permission. The scanner may need the declared `INTERNET` permission for component setup.
- [ ] Inspect application permissions after import, scan, share, and export. There is no request for access to all photos or all files; image and destination access uses the Android pickers.
- [ ] Verify shared files use `content://` FileProvider URIs and temporary read grants. No `file://` URI is sent to another app. The camera receives only its output content URI grant.
- [ ] Confirm the PDF viewer and crop activities are not exported and the FileProvider is not exported. Private draft/PDF directories are not browsable by unrelated apps without a granted URI.
- [ ] After initial scanner setup, import, conversion, viewing, and the private PDF library function without an internet connection. No account or document upload is required by PaperPocket.

## Result record

For each run, keep a concise record:

| Field | Value |
| --- | --- |
| App version / APK hash | |
| Device / Android API | |
| Date / tester | |
| Build, unit tests, lint | See VALIDATION.md; record the build used for this device run |
| Checks passed | |
| Checks failed or skipped | |
| Reproduction steps / screenshots | |

Treat data loss, invalid PDF output, incorrect page order, broken sharing, and app crashes as release blockers. Record scanner/provider availability separately from defects in PaperPocket.


## 1.1.0 beta regression checks

- Update the 1.0.0 release APK with the 1.1.0 signed APK without uninstalling; drafts/PDFs must remain.
- Check Home / Pages / My PDFs labels centered under icons, small screens, and large text.
- Compare Balanced / Compact / High on the same 4 scanned pages; record actual byte sizes and readability.
- Reduce file size from My PDFs; original bytes and page order must remain, and only a smaller copy is saved.
- Open/share/export both original and reduced copies; check no-smaller-output and interrupted/low-storage behavior.
- Record the complete Play Protect warning and phone/Android version. Follow Google’s scan/review guidance; do not disable protection for testers.

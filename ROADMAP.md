# Improving PaperPocket

The first version establishes image import, page arrangement, rotation, document filters, perspective crop, scanner integration with a full-resolution camera fallback, PDF conversion, a private document library, viewing, sharing, and saving copies. Its Android build and device acceptance checks must pass before treating it as ready for release. See `MANUAL_TESTS.md` for the validation checklist.

## Next iteration: reliability

1. Run the acceptance checklist on physical Android devices and fix confirmed defects, especially camera/scanner handoffs, process recreation, provider exports, and low-storage failures.
2. Move long imports and conversion into a lifecycle-independent job with persisted operation state, clear progress, and cancellation. A completed job should update the library even if its screen closes.
3. Add crash-safe cleanup of abandoned temporary files and unreferenced draft images. Preserve files needed by active camera, crop, or export operations.
4. Add Undo for removing a draft page and a recoverable document trash, with explicit permanent deletion.

## Next iteration: scanning and editing

1. Add blur, lighting, and missing-corner feedback so users can retake a poor scan before exporting.
2. Improve local enhancement for shadows and receipts, with an adjustable intensity and before/after preview. Keep the original device photos intact.
3. Add a faster page organizer with drag ordering, multi-select actions, and virtualization for larger drafts/libraries.
4. Evaluate a camera scanner that works on devices without Google Play services, while retaining a clear permission explanation and a usable fallback.

## Later: document tools

1. Add optional on-device OCR and searchable PDFs. A rendered scan currently contains images; selectable/searchable text is a separate feature.
2. Add PDF import, merge, split, and page extraction with clear source/output naming and original-file preservation.
3. Add compression options that show the tradeoff between legibility and file size; measure representative receipts, photos, and mixed documents.
4. Add annotations, signatures, password protection, and folders after the basic workflow is stable.
5. Improve large-screen layouts and accessibility based on device testing and user feedback.

For each improvement, agree on the concrete user behavior, implement it, run the relevant acceptance checks, and document the remaining limits. Choose future work from observed problems and requested features.

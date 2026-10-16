# PaperPocket beta testing and installation

Updated for beta 1.3.0. PaperPocket is currently distributed through GitHub for
phone feedback. It has not been published to Google Play or cleared by Google
Play Protect. iOS and a hosted website are not part of the Android delivery.

## Installing an update

Download the signed release APK from this repository's downloads folder. Version
1.3.0 has version code 4 and keeps the existing application ID and signing
certificate. Android can update the installed app while retaining drafts/PDFs;
uninstalling first is unnecessary. Export important documents before clearing
app data or uninstalling, because private document backup is disabled.

Android's permission to install from a browser/file manager belongs to that
installer. It does not grant PaperPocket access to photos or documents.

## Permissions and document access

The app declares internet/network status, an AndroidX app-private signature
permission, foreground-service/data-sync and wake-lock permissions, and optional
`POST_NOTIFICATIONS`. These additions support conversion started by the user,
with a bounded CPU wake lock while it runs. Android 13+ may ask whether to allow
conversion notifications; refusing does not block PDF creation. Android may
still show its foreground-task indicator even when notifications are denied.

PaperPocket does not request camera, photo/storage, contacts, microphone,
location, SMS, notification-listener, accessibility, or package-installation
permission. Inspect the final merged APK permissions in VALIDATION.md.

Import uses Android's document picker and reads only chosen files. Export asks
the user to choose a destination. Google's scanner uses Google Play services;
camera fallback opens the phone's camera app with a grant for one output image.
These other apps/system components may display their own prompts. Documents are
processed locally; scanner and bundled text-recognition technical metrics remain disclosed in the policy.
See [Google's document scanner documentation](https://developers.google.com/ml-kit/vision/doc-scanner).

## Google Play Protect warning reported by testers

The owner's screenshot on 13 October 2026 shows the heading **App blocked to
protect your device** and the full reason:

> Play Protect hasn't seen an app from this developer before. It may be unsafe.
> Installing this app may put your device at risk.

The reason shown is an unfamiliar developer warning. The screenshot does not
name malware or say the app requests sensitive data. The heading alone is not
enough to classify it as Google's sensitive-permission fraud block. The phone
model, Android version, and installed APK version are still unrecorded.

PaperPocket's local permission/signature checks do not establish Google's
security verdict. Changing the icon, package name, or signing key is not a fix;
changing the key would also break updates for existing testers.

The owner has asked to pause warning investigation while improving the beta.
No new Google appeal, clearance, or warning-removal result is claimed. Keep Play
Protect enabled; installing anyway does not establish a security verdict.
Store publication also cannot guarantee removal of a warning. If this work is
resumed later, record the exact APK version/hash and phone details first.
The earlier owner-review draft has not been submitted to Google.

## What to expect during PDF creation

Normal and ID-card PDF creation continues while the app is minimized and after
activity recreation. A saved in-app status reports completion/cancellation when
you return. Allowing notifications adds progress, Cancel, and completion outside
the app; document names/contents are not placed in lock-screen notifications.
One creation job runs at a time, and draft changes are locked while it runs.
Cancel is unavailable once the final save begins.

Force-stop, reboot, or process termination can interrupt a job and do not cause
automatic retry. Reopen the app to check the result; an unfinished job keeps its
draft for a manual retry. Import, compression, merge/extraction, and OCR still
use their in-app operation flow. Folder/Favorite changes stay local and do not
move documents into a public device folder.

## Review checklist

1. Import 4–10 photos, rearrange, crop, rotate, and convert with Balanced.
2. Scan a receipt and a sheet; check fine text, colors, and page order.
3. Compare Balanced, Compact, and High using the same images. Size depends on
   source detail and page content; no fixed MB-per-page promise is made.
4. In My PDFs, use the menu's Reduce file size option. Confirm the original stays,
   a smaller copy appears, and its before/after sizes and readability are useful.
5. If no smaller output is possible, confirm no extra copy is saved.
6. View, zoom, share, and export both copies; close/reopen and check persistence.
7. Check Home / Pages / My PDFs alignment, small screens, and enlarged fonts.
8. Cancel import/conversion/compression before saving; confirm originals and draft remain.
9. Move a PDF to Trash, Undo, then move again, restart, and Restore. Confirm bytes and name stay intact.
10. Merge two PaperPocket PDFs; check chosen order, page count, and clarity. Extract `1,3-4` and check order.
11. Extract text from a clear English sample; review errors, Copy/Share/Save TXT, and cancel a longer job.
12. Start a larger PDF, minimize/reopen, and check progress/completion. Try optional notification permission both allowed and denied; cancel before saving and confirm the draft remains.
13. Create/rename/delete folders, move PDFs, filter Favorites, and restart. Deleting a folder must keep its documents. Trash/Restore should preserve Favorite/folder details when that folder still exists.
14. Add two non-sensitive sample card photos, front then back, crop close to edges, and create one A4 ID-card page. Check that neither side is stretched or clipped.
15. Record app version, phone model, Android version, exact error, and steps.
   Redact private document content from screenshots and public feedback.

Use [GitHub feedback](https://github.com/murtazaofficialskardu-cloud/PaperPocket/issues/new)
or the in-app support email. Native emulator UI screenshots are included in the store kit. Physical-phone
scan quality and provider feedback remain to be collected before a store launch.
The current CI export preserves native PNGs after Gradle removes its test app.

Previous stable source is retained at tag `v1.2.0`. Report regressions with the
version and steps so a corrected higher-version build can be issued. Android
normally blocks installing an older APK over a newer one; uninstalling first
removes private PDFs/drafts, so export important files before any reset.

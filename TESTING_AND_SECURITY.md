# PaperPocket beta testing and installation

Updated 13 October 2026. PaperPocket is currently distributed through GitHub for
phone feedback. It has not been published to Google Play or cleared by Google
Play Protect. iOS and a hosted website are not part of the Android delivery.

## Installing an update

Download the signed release APK from this repository's downloads folder. Version
1.2.0 has version code 3 and uses the same application ID and signing certificate
as 1.0.0 and 1.1.0. Android can update the installed app while retaining drafts and PDFs;
uninstalling first is unnecessary. Export important documents before clearing
app data or uninstalling, because private document backup is disabled.

Android's permission to install from a browser/file manager belongs to that
installer. It does not grant PaperPocket access to photos or documents.

## Permissions and document access

The inspected APK requests internet/network status and an AndroidX app-private
signature permission. It does not request camera, photo/storage, contacts,
microphone, location, SMS, notification-listener, accessibility, or package
installation permission.

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

Keep Play Protect enabled. On the phone, open Google Play Store → profile →
Play Protect and run its normal scan. If Google offers an app security-check
prompt, use that flow. The supplied screenshot has no Scan button; do not
describe an Install anyway button as a security clearance. Record the result
and the exact APK version/hash before deciding on further review.

For a continuing block believed to be incorrect after the app/SDK audit, the
developer can use Google's official review/appeal route with the exact text,
APK URL/hash, certificate fingerprint, and source evidence. Google's guidance
also says a separate **Send app for security check** prompt is resolved by its
scan flow, and appeals do not remove that particular prompt. The captured
dialog differs from that prompt. Google decides the verdict; a new APK, website,
or eventual store publication cannot guarantee that this warning disappears.
Do not tell reviewers to disable Play Protect.
[Official developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
and [Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals).

The separately delivered `PLAY-PROTECT-REVIEW-DRAFT.txt` contains APK identity,
hash, permission evidence, source URL, and an owner-review draft. It has not been
submitted to Google. The draft now includes the captured warning reason.

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
12. Record app version, phone model, Android version, exact error, and steps.
   Redact private document content from screenshots and public feedback.

Use [GitHub feedback](https://github.com/murtazaofficialskardu-cloud/PaperPocket/issues/new)
or the in-app support email. Native emulator UI screenshots are included in the store kit. Physical-phone
scan quality and provider feedback remain to be collected before a store launch.
The current CI export preserves native PNGs after Gradle removes its test app.

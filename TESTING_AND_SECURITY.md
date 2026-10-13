# PaperPocket beta testing and installation

Updated 13 October 2026. PaperPocket is currently distributed through GitHub for
phone feedback. It has not been published to Google Play or cleared by Google
Play Protect. iOS and a hosted website are not part of the Android delivery.

## Installing an update

Download the signed release APK from this repository's downloads folder. Version
1.1.0 has version code 2 and uses the same application ID and signing certificate
as 1.0.0. Android can update the installed app while retaining drafts and PDFs;
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
processed locally; scanner technical metrics remain disclosed in the policy.
See [Google's document scanner documentation](https://developers.google.com/ml-kit/vision/doc-scanner).

## Google Play Protect warning reported by testers

Some testers reported a Play Protect block with an option to install anyway.
The exact complete dialog and classification have not been captured. The local
permission/signature audit did not find an unnecessary dangerous permission;
it does not establish a Google security verdict or prove a false positive.

For an unknown-app scan prompt, follow Google's security-check flow. For a
harmful/block classification, review the full warning and use Google's official
appeal route if the classification is incorrect. A new APK or website cannot
guarantee removal of the warning. Do not tell reviewers to disable Play Protect.
[Official developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
and [Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals).

The separately delivered `PLAY-PROTECT-REVIEW-DRAFT.txt` contains APK identity,
hash, permission evidence, source URL, and an owner-review draft. It has not been
submitted to Google. Verify the complete warning before selecting an appeal.

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
8. Record app version, phone model, Android version, exact error, and steps.
   Redact private document content from screenshots and public feedback.

Use [GitHub feedback](https://github.com/murtazaofficialskardu-cloud/PaperPocket/issues/new)
or the in-app support email. Clear real-phone screenshots remain to be collected
before a store launch. The native navigation tests pass, but CI screenshot
export was unavailable after Gradle uninstalled its test app.

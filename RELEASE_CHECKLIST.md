# PaperPocket release checklist

Updated for the free 1.4.0 beta (version code 5). Current build, native device, signing, and artifact evidence is in [VALIDATION.md](VALIDATION.md). Physical-phone scanner/camera/provider acceptance and Play approval remain separate requirements.

Production launch is postponed for beta feedback. The supplied Play Protect screenshot identifies an unfamiliar developer warning; the owner has paused its investigation. Google clearance is not claimed. See [TESTING_AND_SECURITY.md](TESTING_AND_SECURITY.md).

The confirmed public support/privacy contact is **murtazaofficialskardu@gmail.com**. The [rendered privacy policy](https://github.com/murtazaofficialskardu-cloud/PaperPocket/blob/main/PRIVACY_POLICY.md) was checked publicly readable without login on 10 October 2026. Play Console submission and approval have not been established by these documents.

## GitHub beta release

The owner approved a one-time signing reset. Version 1.4 keeps the package ID
but uses a new distribution certificate. It cannot update 1.3 or earlier in
place: export wanted PDFs, uninstall the old beta, then install 1.4. Uninstalling
removes private PDFs, drafts, folders and Favorites. External PDF import and
automatic migration are not implemented. Future 1.4-key builds retain normal
update support. No device reset or uninstall is performed by this repository.

- [x] Owner accepted the fresh-install beta plan.
- [x] Source checks pass for the existing app checkpoint; see VALIDATION.md.
- [ ] Build/sign final distribution artifacts with the new 1.4 key and verify package, version, certificate, signatures and alignment.
- [x] Restore the durable owner-private Google Drive signing backup: owner-only permissions, downloaded ZIP checksum/certificate match, and restored-key JAR signing/verification passed. Keep secrets and private backup links out of public Git/archives.
- [ ] Publish versioned APK/AAB/source/kit, checksums and the exact CI record. Verify anonymous download bytes and update the public website only after the APK exists.
- [ ] Make the 1.3-to-1.4 fresh-install instruction visible on the website and download page.

The later Play/physical-phone checklist below is separate from this small free
beta delivery; no Play approval or universal device certification is claimed.

## Artifact and build record

| Item | Required evidence | Current record |
| --- | --- | --- |
| Application ID | Final merged manifest uses `io.github.murtazaofficialskardu_cloud.paperpocket` | Verified release metadata |
| Target API | `targetSdkVersion` is 36 or higher in the actual APK/AAB | Verified target 36; minimum 26 |
| Version | Final version name and monotonically increasing version code | 1.4.0 beta, version code 5 |
| Native build | Debug APK, release APK/AAB, unit tests, and relevant lint succeed | Verified source matrix: 11 unit; API 26 74 pass; API 36 normal/16 KB 73 pass + 1 expected skip each |
| Signing | APK/AAB signatures, new certificate and durable backup recovery | Verify at release packaging and record in the 1.4 manifest; old 1.3 identity is not retained |
| Device acceptance | Completed device matrix and critical flows below | Use version-specific automated evidence in VALIDATION.md; physical scanner/camera/provider and full manual matrix remain pending |
| Store content | Complete listing, graphics, screenshots, rating, app-content forms, and public policy URL | Partly prepared; publication steps pending |

New mobile apps and updates submitted after 31 August 2026 must target Android 16/API 36 or newer. Recheck the current [official target API requirement](https://developer.android.com/google/play/requirements/target-sdk) at submission time.

- [ ] Confirm the intended package ID before the first Play upload. Keep it stable for future updates.
- [x] Build and record the output of `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:bundleRelease :app:lintRelease`.
- [ ] Record final signed artifact filenames, size, SHA-256, signer and source provenance in release-manifest-1.4.0.json.
- [x] Inspect APK/AAB metadata and the merged manifest. Verify the viewer/crop activities and FileProvider are not exported, backup exclusions remain effective, and only intended permissions appear.
- [x] Inspect the resolved runtime dependency tree and final merged permissions. Source declarations alone do not account for transitive SDK manifests. Investigate camera, broad media/storage, advertising-ID, or unrelated permissions if present.
- [x] Version 1.2 bundled OCR was tested on x86_64 API 36 with 16 KB pages and compatibility disabled; 64-bit LOAD/ZIP alignment passes. VALIDATION.md records the RELRO static-check caveat and remaining ARM64/Play checks. See [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- [ ] Keep the 1.4 distribution keystore and passwords outside Git/logs, verify the final APK/AAB signer, and verify durable private backup recovery. A valid signature is separate from an observed install on a phone.
- [ ] Before enrolling in Play App Signing, decide how existing GitHub APKs will update. An automatically generated Play app-signing key differs from the current GitHub certificate unless the existing key is selected as the distribution key through the supported enrollment flow. Test that migration using the retained 1.4 distribution key and use a separate upload key where appropriate. Retain recovery information. An AAB is a publishing format; it cannot be installed directly like an APK. Google generates distribution APKs after upload. See [Android app signing](https://developer.android.com/studio/publish/app-signing).
- [ ] Validate and test APKs generated from the release AAB with bundletool or an internal Play test, rather than relying only on a debug APK.
- [ ] For a later same-1.4-key build, test an in-place version increase and retain drafts/PDFs. This does not apply to the intentional fresh install from the pre-1.4 key.

## Device release gate

Run [MANUAL_TESTS.md](MANUAL_TESTS.md), recording device, Android API, app version, and reproduction details for each failure. Include Android 8/API 26, Android 15/API 35, Android 16/API 36, a supported physical Play services scanner device, and an unsupported-scanner/camera-fallback setup.

- [ ] Import single/multiple images, EXIF orientation, transparency, invalid inputs, cloud-provider inputs, and the 100-page limit.
- [ ] Verify arrangement, rotation, filters, perspective crop, crop cancellation, and draft persistence.
- [ ] Scan a real sheet and receipt; test component download, cancellation, cleanup, and full-resolution camera fallback.
- [ ] Independently open High/Balanced/Compact exports and verify page order, page count, paper proportions, white margins, crop, rotation, and fine-text legibility.
- [ ] Verify the app explains source-detail limits. High/Balanced/Compact are export resolution targets, not a guarantee that a capture contains the corresponding detail.
- [ ] Verify PDF viewing, zoom/pan, boundary navigation, rotation state, sharing read grants, and save-copy success/cancellation/failure.
- [ ] Verify library search/sorting within All PDFs, Favorites, and folder filters. Check folder create/rename/move, delete-folder-keeps-PDFs, and Favorite/folder retention across Trash/Restore. Existing pre-1.3 PDFs remain readable without metadata loss.
- [ ] Verify document rename, deletion confirmation, and exported-copy independence.
- [ ] Reduce file size with each sharing preset. Before Save, compare actual bytes, inspect pages and confirm the library count is unchanged. Save only a strictly smaller candidate, check its bytes, and verify Discard/cache expiry retains the original.
- [ ] Create an ID-card PDF from exactly two cropped photos, front then back. Check one portrait A4 output page, aspect-preserving placement in card-size frames, selected quality/filter/rotation, and cancel/error cleanup. Invalid photo counts explain the requirement without changing the draft.
- [ ] Minimize/recreate the activity during normal and ID-card PDF creation, including Don't keep activities. Verify progress/result recovery, no duplicate PDF, Cancel before final saving, draft edit locks, and clearing only the exact completed draft. Test import and other in-app operations separately; they do not yet promise lifecycle-independent completion.
- [ ] On Android 13+, allow and deny optional notifications. Creation must work in both cases; notification Cancel/tap actions work when allowed, and no names/contents leak to the lock screen. Force-stop/reboot/process termination must not trigger automatic retry; a stopped job keeps a retryable draft.
- [ ] Test stale Cancel/start notification actions across back-to-back jobs; an old action must not cancel or duplicate the new PDF. Verify bounded wake-lock cleanup and no ongoing notification after a canceled/completed job.
- [ ] Test low-storage and low-memory failures; keep the draft recoverable and incomplete PDFs out of the library.
- [ ] On API 36, verify edge-to-edge insets, cutouts, three-button/gesture navigation, and Back behavior. Include resizable tablet/foldable windows and enlarged fonts. See [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).
- [x] Run connected Android integration tests and record the result of `:app:connectedDebugAndroidTest`.
- [ ] Read the complete in-app privacy policy without an internet connection and verify the support contact matches the listing.

Any confirmed data loss, stale-draft overwrite, invalid PDF output, wrong page order, broken share/export access, or crash blocks release. A successful compile does not replace these tests.

## Privacy and Data safety

The current code keeps document contents local except when the user deliberately shares or exports. It has no publisher-operated analytics, accounts, billing, or ad SDK. The Google scanner and bundled Latin text-recognition SDK require SDK-aware disclosures.

- [ ] Compare [PRIVACY_POLICY.md](PRIVACY_POLICY.md), `app/src/main/assets/privacy_policy.txt`, and `privacy_policy.html` with the final code and dependencies; keep them synchronized.
- [ ] Enter the [public rendered policy URL](https://github.com/murtazaofficialskardu-cloud/PaperPocket/blob/main/PRIVACY_POLICY.md) in Play Console and keep the repository public. This read-only HTML policy page is an available option; alternatively host the supplied standalone HTML at an active, public HTTPS page without login. Recheck the chosen URL signed out at submission time. A private repository URL or local asset path is not a public policy URL.
- [ ] Confirm the app's Privacy & support entry displays the policy and the confirmed email. Play requires an in-app policy and a public policy link; see [Play's user-data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- [ ] Review the exact resolved scanner and text-recognition SDK versions against [ML Kit's disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure). Its metrics include technical device/app details, identifiers, diagnostic and usage information; map these to the current form's data categories and purposes.
- [ ] Account for Google's SDK metrics when assessing collection. Do not answer “no data collected” simply because PDFs remain local. Confirm identifiers, analytics purposes, optional/required status, and retention using the resolved SDK's documentation.
- [ ] Assess sharing under Play's definitions and applicable service-provider/user-initiated-action exceptions; do not infer a blanket answer from local document storage.
- [ ] Verify transit-security answers against every collecting SDK. Distinguish transmission security from PDF password protection, which is not implemented.
- [ ] Do not claim Google metric deletion is provided by deleting a local PDF. Confirm any deletion-request answer with the relevant provider's policy.
- [ ] Review the disclosure each time SDKs, networking, advertising, accounts, or cloud features are changed.
- [ ] Check whether any prominent disclosure/consent is required for the final SDK configuration and distribution audience. No consent mechanism or collection exemption is assumed by this checklist.

Core Google Play services libraries and the ML Kit scanner have separate disclosure guidance; [the core SDK page](https://developers.google.com/android/guides/play-data-disclosure) does not establish that every Google SDK collects no data.

## Play Console and distribution

- [ ] Complete the developer account's identity/contact verification and app setup. Confirm countries, pricing, category, and target audience.
- [ ] Add the prepared text from [PLAY_STORE_LISTING.md](PLAY_STORE_LISTING.md), a real store icon, feature graphic, and screenshots captured from a verified installed build.
- [ ] Declare the user-started `dataSync` foreground service for local PDF processing if requested by Console. Provide the accurate purpose/demo for normal and ID-card conversion; no boot-start or automatic restart is implemented. Verify current service policy before submission.
- [ ] Complete Data safety, ads, app access, content rating, and all other app-content declarations. The implemented app needs no reviewer login.
- [ ] Use the internal track to test the signed bundle and address Play's pre-launch report before production.
- [ ] If this is a personal developer account created after 13 November 2023, complete its qualifying closed test before requesting production access: the currently documented requirement is at least 12 testers continuously opted in for 14 days. Check the account's actual Console requirement and [official testing guidance](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [ ] Review the final release notes, artifact, declarations, and privacy URL together before submitting. Play review/approval remains a separate step from building a valid AAB.
- [ ] Record the submission track/version and subsequent review result. Mark the release ready only when the required evidence above is complete.

## GitHub versioning and recovery

- [ ] Keep previous public artifacts and stable tags; `v1.3.0` preserves the previous signing epoch and its artifacts. Push each completed change so its history is reviewable.
- [ ] If beta feedback finds a regression, starting from 1.4, release a corrected build with the retained 1.4 package/signing key and a version code above the installed version. An older APK is normally rejected as an in-place downgrade.
- [ ] Export important documents before any uninstall/reset experiment. Never describe uninstalling to install an older APK as a data-preserving rollback.

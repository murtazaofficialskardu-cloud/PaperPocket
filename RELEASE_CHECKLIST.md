# PaperPocket release checklist

Updated on 10 October 2026. Native builds, all 8 Gradle unit tests, debug/release lint, and release signature verification have passed; exact results and artifact hashes are in [VALIDATION.md](VALIDATION.md). All 21 API 26 instrumentation tests have passed. Physical-device acceptance, release UI/screenshots, and Play approval remain pending. This checklist separates produced build artifacts from the remaining release work.

The confirmed public support/privacy contact is **murtazaofficialskardu@gmail.com**. The [rendered privacy policy](https://github.com/murtazaofficialskardu-cloud/PaperPocket/blob/main/PRIVACY_POLICY.md) was checked publicly readable without login on 10 October 2026. Play Console submission and approval have not been established by these documents.

## Artifact and build record

| Item | Required evidence | Current record |
| --- | --- | --- |
| Application ID | Final merged manifest uses `io.github.murtazaofficialskardu_cloud.paperpocket` | Verified release metadata |
| Target API | `targetSdkVersion` is 36 or higher in the actual APK/AAB | Verified target 36; minimum 26 |
| Version | Final version name and monotonically increasing version code | 1.0.0, version code 1 |
| Native build | Debug APK, release APK/AAB, unit tests, and relevant lint succeed | Passed; 8/8 tests; 0 errors and 31 warnings per lint variant |
| Signing | Installable APK signature and upload-key signature on release AAB | Verified v2 APK and AAB JAR signatures, RSA 4096-bit key |
| Device acceptance | Completed device matrix and critical flows below | API 26 automated tests passed; physical scanner/camera, release UI, and full manual matrix pending |
| Store content | Complete listing, graphics, screenshots, rating, app-content forms, and public policy URL | Partly prepared; publication steps pending |

New mobile apps and updates submitted after 31 August 2026 must target Android 16/API 36 or newer. Recheck the current [official target API requirement](https://developer.android.com/google/play/requirements/target-sdk) at submission time.

- [ ] Confirm the intended package ID before the first Play upload. Keep it stable for future updates.
- [x] Build and record the output of `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:bundleRelease :app:lintRelease`.
- [x] Record the exact artifact filenames, version code/name, size, SHA-256, target API, build-tool versions, and commit.
- [x] Inspect APK/AAB metadata and the merged manifest. Verify the viewer/crop activities and FileProvider are not exported, backup exclusions remain effective, and only intended permissions appear.
- [ ] Inspect the resolved runtime dependency tree and final merged permissions. Source declarations alone do not account for transitive SDK manifests. Investigate camera, broad media/storage, advertising-ID, or unrelated permissions if present.
- [x] Inspect the final archive for native `.so` files. If present, verify their 16 KB compatibility and test on a 16 KB device/emulator. Pure Java/Kotlin apps need no native-library alignment work, but the assembled dependencies determine that conclusion. See [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).
- [x] Keep the keystore and signing passwords outside Git and release logs. Verify the APK is installable and the AAB uses the intended publisher upload key.
- [ ] Enroll in Play App Signing and retain the upload-key recovery information. An AAB is a publishing format; it cannot be installed directly like an APK. Google generates distribution APKs after upload. See [Android app signing](https://developer.android.com/studio/publish/app-signing).
- [ ] Validate and test APKs generated from the release AAB with bundletool or an internal Play test, rather than relying only on a debug APK.
- [ ] Test upgrading an installed build with the intended signing identity and version increase. Drafts and saved PDFs must survive a supported update.

## Device release gate

Run [MANUAL_TESTS.md](MANUAL_TESTS.md), recording device, Android API, app version, and reproduction details for each failure. Include Android 8/API 26, Android 15/API 35, Android 16/API 36, a supported physical Play services scanner device, and an unsupported-scanner/camera-fallback setup.

- [ ] Import single/multiple images, EXIF orientation, transparency, invalid inputs, cloud-provider inputs, and the 100-page limit.
- [ ] Verify arrangement, rotation, filters, perspective crop, crop cancellation, and draft persistence.
- [ ] Scan a real sheet and receipt; test component download, cancellation, cleanup, and full-resolution camera fallback.
- [ ] Independently open high/standard quality exports and verify page order, page count, paper proportions, white margins, crop, rotation, and fine-text legibility.
- [ ] Verify the app explains source-detail limits. High/standard are export resolution targets, not a guarantee that every capture contains 300/150 DPI detail.
- [ ] Verify PDF viewing, zoom/pan, boundary navigation, rotation state, sharing read grants, and save-copy success/cancellation/failure.
- [ ] Verify library search, sorting, rename, deletion confirmation, and exported-copy independence.
- [ ] Background/recreate the activity during import and conversion, including Don't keep activities and return from external pickers. Existing draft edits must not be overwritten by an old worker's snapshot.
- [ ] Test low-storage and low-memory failures; keep the draft recoverable and incomplete PDFs out of the library.
- [ ] On API 36, verify edge-to-edge insets, cutouts, three-button/gesture navigation, and Back behavior. Include resizable tablet/foldable windows and enlarged fonts. See [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).
- [x] Run connected Android integration tests and record the result of `:app:connectedDebugAndroidTest`.
- [ ] Read the complete in-app privacy policy without an internet connection and verify the support contact matches the listing.

Any confirmed data loss, stale-draft overwrite, invalid PDF output, wrong page order, broken share/export access, or crash blocks release. A successful compile does not replace these tests.

## Privacy and Data safety

The current code keeps document contents local except when the user deliberately shares or exports. It has no publisher-operated analytics, accounts, billing, or ad SDK. The Google scanner still requires an SDK-aware disclosure.

- [ ] Compare [PRIVACY_POLICY.md](PRIVACY_POLICY.md), `app/src/main/assets/privacy_policy.txt`, and `privacy_policy.html` with the final code and dependencies; keep them synchronized.
- [ ] Enter the [public rendered policy URL](https://github.com/murtazaofficialskardu-cloud/PaperPocket/blob/main/PRIVACY_POLICY.md) in Play Console and keep the repository public. This read-only HTML policy page is an available option; alternatively host the supplied standalone HTML at an active, public HTTPS page without login. Recheck the chosen URL signed out at submission time. A private repository URL or local asset path is not a public policy URL.
- [ ] Confirm the app's Privacy & support entry displays the policy and the confirmed email. Play requires an in-app policy and a public policy link; see [Play's user-data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
- [ ] Review the exact resolved scanner SDK version against [ML Kit's disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure). Its metrics include technical device/app details, identifiers, diagnostic and usage information; map these to the current form's data categories and purposes.
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
- [ ] Complete Data safety, ads, app access, content rating, and all other app-content declarations. The implemented app needs no reviewer login.
- [ ] Use the internal track to test the signed bundle and address Play's pre-launch report before production.
- [ ] If this is a personal developer account created after 13 November 2023, complete its qualifying closed test before requesting production access: the currently documented requirement is at least 12 testers continuously opted in for 14 days. Check the account's actual Console requirement and [official testing guidance](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [ ] Review the final release notes, artifact, declarations, and privacy URL together before submitting. Play review/approval remains a separate step from building a valid AAB.
- [ ] Record the submission track/version and subsequent review result. Mark the release ready only when the required evidence above is complete.

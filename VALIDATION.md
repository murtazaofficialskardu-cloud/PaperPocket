# PaperPocket 1.1.0 beta validation

Recorded 13 October 2026. Application ID remains
`io.github.murtazaofficialskardu_cloud.paperpocket`; version code 2, minimum API 26,
target API 36. The signed APK shares the 1.0.0 certificate and supports an in-place
update. APK/AAB signatures and official bundletool 1.18.3 validation pass.

## Executed checks

- SDK 36 debug/release APK, signed AAB, and native test APK build passed.
- 8 unit tests passed; debug/release lint each has 0 errors and 31 warnings.
- Android 8/API 26: 25 tests passed.
- Android 16/API 36: 24 tests passed, one expected Android 8-only skip, none failed.
- Navigation tests verified centered labels under icons, equal widths, minimum touch targets, untruncated labels, and working tabs on real emulator windows.
- Compression tests verified Compact < Balanced < High < prior JPEG98 exports on the fixture, preserved color/text contrast and page dimensions/order, preserved original bytes, and successful recovery after malformed input.
- Recompression fixture was smaller than 70% of its original. This is a test fixture result; no fixed size guarantee is made for the user’s scans.
- Portable writer: 61 checks plus independent rendering of all 103 generated PDF pages passed.
- APK contains no bundled native .so libraries and requests only internet/network status and an app-private AndroidX signature permission.

GitHub Actions passed for beta commit `f73272a`: [build and Android device tests](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/38349974136).

- APK/AAB builds and debug/release lint passed; **8 unit tests passed**.
- Android 8.0 / API 26: **25 tests passed**, none skipped or failed.
- Android 16 / API 36: **24 tests passed**, one expected skip for the Android 8-specific verifier test; none failed.
- Compression tests verified smaller profile outputs, preserved text contrast and color, unchanged original PDF bytes, and recovery after malformed input.

Counts were verified from uploaded JUnit XML. The CI screenshot export produced error text after app uninstallation; valid UI screenshots still need collection.

## Verification limits

Real phone feedback confirms basic 1.0.0 use, but the new 1.1.0 scan/camera,
sharing/provider, font/device matrix, lifecycle and low-resource acceptance must
still be checked. CI screenshots were captured inside the native test but the
post-task export found the app uninstalled; invalid text files were excluded.
No clean store screenshots are claimed. The broken export was removed from
future CI; native UI assertions remain.

Google Play Protect’s reported classification has not been cleared or submitted
for review in this session. An audit/signature does not establish a malware-scan
verdict. Use TESTING_AND_SECURITY.md and the owner-review draft.

Play Store launch is postponed. No iOS app or hosted Site was created. Recompression
makes image-only copies and does not preserve selectable text/links/interactive
content; the original is retained by the app workflow.

Build/test reports are in app/build/reports/ and app/build/test-results/.
Versioned public downloads and SHA-256 records are in downloads/.

# PaperPocket 1.4.0 beta

Version name 1.4.0, version code 5; free Android 8+ GitHub beta.
[Download the APK](https://github.com/murtazaofficialskardu-cloud/PaperPocket/raw/refs/heads/main/downloads/PaperPocket-1.4.0.apk).

## Installing from 1.3 or earlier

This release starts a new signing identity with the same package ID. Android
cannot install it over a beta signed with the earlier key. Export wanted PDFs
through My PDFs → document menu → Save a copy, then uninstall the old beta and
install 1.4. Uninstalling removes private PDFs, drafts, folders and Favorites.
External PDF import and automatic migration are not implemented. Keep exported
copies in your chosen destination. Future releases using the retained 1.4 key
can update an installed 1.4 normally.

The owner approved this fresh-install beta reset. It is separate from any
Play Protect classification; no Google clearance or Play publication is claimed.

## Compression review

Open a saved PDF's actions → Reduce file size. Choose WhatsApp/chat,
Email/everyday or Fine print/forms, which map to Compact, Balanced and High.
A preset does not promise a service's attachment limit or an exact file size.

Compare & preview prepares an unsaved private candidate and shows actual
before/after bytes and savings. Preview pages opens the full reader without
share/export controls. Return to Save smaller copy or Discard. Saving accepts
only a strictly smaller candidate and keeps the original. Preparing or viewing
it never creates a library entry by itself.

Saved activity state can restore a review while its cache file exists. Android
may evict cache earlier; abandoned candidates older than 24 hours are cleaned
on the next app start or preparation. A fresh launch without saved activity
state does not automatically reopen an unsaved review. Compression remains an
activity-bound cancellable operation.

## Executed source checks

Tested app/test checkpoint: `26480f45ff9641063846ab2b79d4aa315fbd708b`.
[Verified CI run](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/41073341442):
11 unit tests passed; API 26 had 74 passes; API 36 normal and strict 16 KB each
had 73 passes and one expected legacy-only skip. No failures/errors. Debug and
release lint each had zero errors and 37 warnings. All four report ZIP hashes
were verified against GitHub digests; strict runtime reported 16,384-byte pages
with compatibility disabled. [Exact CI record](validation/1.4.0/final-ci-results.json).

Four new tests cover candidate cleanup/restore, original bytes, exact reviewed
output, larger-result refusal, unsafe IDs/symlinks and Main → private reader →
explicit Save. These are emulator/source checks, not certification of every
physical phone or of the final signed APK installation.

## Release signing and recovery

The new epoch-2 distribution certificate SHA-256 is:

`9c013d3bdf3a96b66cddf70d1c5d55361d157d5f13ab2e9308ec4967330c2f30`

Use the exact artifact hashes and provenance in the versioned
[release manifest](downloads/release-manifest-1.4.0.json). Keep the 1.4 private
keystore, passwords and recovery archive out of Git and all public downloads.
The epoch-2 recovery archive was saved to the owner's private Google Drive.
Permissions were checked as owner-only, with no public/domain sharing. A fresh
download matched the archive checksum and certificate, and the restored private
key successfully signed a JAR whose signature was verified. A temporary
workspace is no longer the only recovery copy.

For physical feedback, check fine text at each preset, preview navigation,
large-font dialog buttons, Cancel, low storage, cache eviction and activity
recreation. No new Play Store, iOS, ads, billing or paywall is part of this beta.

# PaperPocket 1.4 development beta

Version name 1.4.0, version code 5. This branch adds compression review to the
Android app. It is **not a published signed APK update**. The current installable
release remains [1.3.0](https://github.com/murtazaofficialskardu-cloud/PaperPocket/raw/refs/heads/main/downloads/PaperPocket-1.3.0.apk).

## Changed behavior

Open a saved PDF's actions, choose **Reduce file size**, then select WhatsApp/chat,
Email/everyday or Fine print/forms. These map to Compact, Balanced and High
quality; they do not promise a service's attachment limit or an exact size.

**Compare & preview** prepares an unsaved private candidate. The review shows
actual before/after bytes and savings. **Preview pages** opens the full reader
without sharing or export controls. Return to choose **Save smaller copy** or
**Discard**. Saving is enabled only for a strictly smaller result and keeps the
original. No library entry is added just by preparing or viewing a candidate.

Saved activity state can restore a pending review while its cache file exists.
Android may evict cache; abandoned candidates older than 24 hours are cleaned on
the next app start or preparation. A fresh launch without saved activity state
does not automatically reopen an unsaved review. Compression remains an
activity-bound cancellable operation; this does not extend foreground-service
background completion to compression.

## Validation status

App/test source checkpoint: `26480f45ff9641063846ab2b79d4aa315fbd708b`.

- Local SDK 36 debug/release APK, unsigned AAB and test APK builds passed.
- 11 local unit tests passed; debug and release lint each recorded zero errors
  and 37 warnings.
- The native matrix is recorded in [GitHub Actions](https://github.com/murtazaofficialskardu-cloud/PaperPocket/actions/runs/41073341442).
  Its results must be verified before marking this branch ready for release.
- Four new native tests cover candidate persistence/cleanup, exact reviewed
  bytes, original preservation, larger-result refusal, unsafe IDs/symlinks, and
  Main → private reader → explicit Save. The intended matrix is 74 tests per
  device, with one existing legacy-only skip on API 36.

Run on physical phones before release: small-print readability at each preset,
large fonts and dialog buttons, multipage preview navigation, Cancel, low
storage, cache eviction, activity recreation and updating without uninstalling.

## Signing requirement

The fresh workspace has no original private upload keystore or signing secrets.
They were deliberately excluded from Git. Release outputs from this workspace
are unsigned unless the owner restores the private backup; debug outputs use a
development certificate and are not an update for installed release APKs.

Restore the owner's original signing backup privately before publishing 1.4.0.
The expected publisher certificate SHA-256 is
`39fa4e90865d4d71ae53570fb8a86c5cd9845c3a55cb74d50f5e316126499f25`.
Never commit keys, passwords or the private backup. Do not replace that identity
or tell users to uninstall merely to install this development build.

The download/support website continues advertising signed 1.3.0 until a new
signed release is tested, published and checked against its public checksum.

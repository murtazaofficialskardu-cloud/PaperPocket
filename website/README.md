# PaperPocket download and support website

Buildless HTML, CSS and JavaScript for the free Android beta. The current
download is the signed **1.3.0**, version code **4**, APK for Android 8 or newer.
The website does not offer browser PDF conversion, an iOS app, or a Play Store
listing. Android changes under development do not change the advertised stable
download until a signed release is available.

## Deployment and source

- Site URL: https://paperpocket.sm-ali.chatgpt.site
- Current audience: **owner-private**. Public audience preference is pending;
  this is not a public website launch announcement.
- Site project: `appgprj_6ae4a7307cb48191b2869e0dd24ac313`.
- Mirrored Sites source commit: `d2f60d36510c02a8f1a74cfa95cb12aea0ff804b`.
- Native Sites deployment returned success. Local browser checks are recorded in
  [QA.md](QA.md); deployment success is separate from public access.

This folder mirrors the dedicated Sites source repository. A GitHub commit here
does not automatically redeploy the Site. Use the same Site identity and its
supported source/version/deployment workflow, then keep this mirror synchronized.
The hosting manifest contains the project ID and static output path, not a token.
Credentials, local runtime files and Git metadata are not included.

## Layout

- `dist/index.html`: landing page, Android download, installation guide,
  screenshot tabs, release details, FAQ and feedback form.
- `dist/styles.css` and `dist/app.js`: responsive styles and browser interactions.
- `dist/assets/`: real Android 1.3 screenshots, app icon and release manifest.
- `dist/privacy/index.html`: the Android app privacy policy, preserved from the
  release source.
- `dist/privacy/website.html`: separate website privacy information.
- `.openai/hosting.json`: static deployment root is `dist`.
- `qa-evidence/`: local browser captures and results; excluded from static output
  because it is outside `dist`.

The app screenshots show genuine native emulator screens. They are not a web
converter interface, real-phone camera-quality evidence, or invented user reviews.

## Local preview

From the repository root, serve the static directory with an available HTTP
server, for example:

```sh
python3 -m http.server 4173 --bind 127.0.0.1 --directory website/dist
```

Open the server's local address on the same machine. No package installation or
build step is required. Remote execution may require supported port forwarding.

## Downloads and future releases

The current APK is 45,866,079 bytes, approximately 45.9 MB or 43.7 MiB. Its public
GitHub URL and SHA-256 are in `dist/assets/release-manifest-1.3.0.json`.
The signed APK is installable; the AAB publishing format is not a direct install.

Update advertised releases only after a new APK is signed with the original
publisher identity, tested, published and checked against its public hash. Keep
older versioned files and source history. Never replace the download with a
debug APK, unsigned build, or an in-progress Android branch.

For a new stable release, update the bundled manifest, HTML download/version/size
copy, `app.js` manifest filename and artifact key, and feedback version together.
Refresh screenshots only with genuine captures of the advertised app version.
Check download links, hashes, privacy disclosures and browser behavior again.

## Feedback and privacy

The form prepares an email draft addressed to
`murtazaofficialskardu@gmail.com`; the visitor reviews and sends it in their mail
app. The website does not submit or store feedback. Copy report and its manual
fallback let visitors use email or a GitHub issue instead.

The frontend adds no analytics, advertising scripts, document-upload server,
accounts, payments, or feedback database. Browser form fields are not persisted
by this site's code. Hosting, GitHub downloads and the visitor's mail provider
have their own request handling; see the separate website privacy page.


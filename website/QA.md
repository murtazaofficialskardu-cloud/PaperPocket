# Website verification

Local checks were performed with Chromium **154.0.8037.57**. Raw results and two
hero captures are in [qa-evidence/](qa-evidence/). These are local browser checks;
they do not certify physical phones or public Site access.

| Check | Observed result |
| --- | --- |
| Desktop width 1440 px | Document and body width 1440 px; no page overflow |
| Mobile width 390 px | Document and body width 390 px; no page overflow |
| Narrow width 320 px | Document and body width 320 px; no page overflow |
| Enlarged text at 390 px | Relevant text doubled from initial computed sizes; no page overflow |
| JavaScript errors | None recorded |
| Failed HTTP responses | None recorded |
| Native screenshot tabs and keyboard navigation | Passed |
| APK checksum clipboard action | Passed |
| Feedback required fields and draft URL encoding | Passed |
| Feedback copy action and clipboard-denied fallback | Passed |
| Unintended network mutations | None recorded |

The 320 px diagnostic noted a decorative back-paper element extending about
10 px beyond the left boundary. It remained clipped; the page width stayed
320 px and interactive content did not produce horizontal page scrolling.
The responsive fix was separately recorded as verified. The enlarged-text test
changes computed browser text sizes; it is not a physical-phone OS font test.

Local HTTP checks returned 200 for the app icon, stylesheet, script, Home/Pages
screenshots, Android app privacy page and separate website privacy page. The
public APK link also returned anonymous HTTP 200 with Content-Length
45,866,079, matching the stable 1.3 release manifest.

A link-arrow glyph issue found in visual inspection was normalized to a standard
right arrow before the published source commit. Hero images are local QA
evidence; the tracked `dist` files are the deployed source mirror.

Native Sites reported a successful deployment for source commit
`d2f60d36510c02a8f1a74cfa95cb12aea0ff804b` at
https://paperpocket.sm-ali.chatgpt.site. The owner requested public access on
31 October 2026. Sites confirmed public access policy revision 2. Separate
anonymous homepage and app privacy GET requests returned HTTP 200 with actual
PaperPocket content, no cookies or authorization, and no sign-in redirect. An
initial transient HTTP 403 cleared during verification; its cause was not established.

## Evidence captures

- [Desktop hero](qa-evidence/desktop-hero.png)
- [Mobile hero](qa-evidence/mobile-hero.png)
- [Raw browser results](qa-evidence/results.json)


The form also has a native `mailto:` action for script-disabled fallback; it has
no HTTP feedback endpoint. This HTML-only follow-up does not alter the verified
JavaScript draft/copy flow or page layout.

# PaperPocket product plan

Updated 13 October 2026. The owner has chosen to improve the free app first.
Monetization is a later decision. The eventual goal is **US$10,000 total
earnings**, not monthly revenue or a download target. No purchases, ads,
paywall, payment SDK, or publisher analytics have been added.

## Current beta: finish the document workflow

The product promise is straightforward: scan or import, arrange pages, create
a readable PDF at a sensible size, view it, and share it. Preserve original
files when compressing or extracting pages. Make cancellation understandable
and deleting a document reversible. Keep the initial screen simple.

Version 1.2 adds cancellation/page progress, persistent PDF Trash/Restore,
lossless merge and selected-page extraction for supported PaperPocket PDFs,
and optional bundled on-device Latin text recognition. These are beta features
to validate on phones. Text recognition returns editable/copyable text; it does
not add a searchable layer to a PDF or promise Urdu/handwriting recognition.

The reported installer screenshot identifies an unfamiliar developer warning.
The app cannot clear Google's verdict by changing its code or signing key.
Follow [TESTING_AND_SECURITY.md](TESTING_AND_SECURITY.md); do not disable Play
Protect. Play Store publication stays on hold for now.

## Test with people before expanding scope

Start with 10 invited testers, then 30 after the main defects are fixed. Include
Android 8 and a recent Android version, at least three phone brands, a modest
RAM/storage device, enlarged fonts, and a device where Google's scanner is
unavailable. This is a proposed test sample, not a report of completed testing.

For each tester record version, phone/Android version, task completed or failed,
reason, time to first PDF, page count, file size/profile, readability, and whether
they choose to use the app again the next week. Ask users to report these facts;
do not collect their documents or install tracking without a separate decision
and updated disclosures. Use the GitHub phone-feedback form.

Before considering a store launch, require no known document-loss defect and
successful import/scan, convert, cancel, trash/restore, view, share/export, and
restart checks across that test sample. Review any recurring failure before
adding more features. Compare the same four-page sample in Balanced, Compact,
and High. A small PDF is useful only if its text is still readable.

Next improvements should follow observed problems: lighting/blur feedback,
undo for draft-page removal, durable background jobs, faster large-draft page
ordering, and a simpler onboarding flow. Later, evaluate searchable PDFs,
additional text scripts, folders, annotations, and general PDF import.

## Earning later

Keep the present promised workflow free while reviewing usage and feedback.
After users return regularly, ask which additional capability they value enough
to pay for. Evaluate a one-time Pro upgrade before assuming a subscription is
appropriate for an offline utility. Subscription pricing would need sustained
additional value. Neither a price nor a paid feature set has been chosen.

Track cumulative customer receipts, refunds, store fees, currency conversion,
taxes, development/hosting/support costs, and actual payouts separately. Gross
sales are not take-home profit. Illustrative arithmetic: 2,000 purchases at
US$5 produce US$10,000 gross. At an assumed 15% store fee they leave US$8,500
before taxes, refunds, conversion, and costs. The actual fee depends on region,
billing/program enrollment, and the rules in effect when selling; this example
is not a forecast or a current pricing proposal.

Do not spend on advertising until users retain the app and a paid offering has
been validated. Organic demonstrations and the owner's video can show real
scan quality, file-size tradeoffs, and the privacy model when the beta is ready.

## Distribution and country

Google currently lists both Indonesia and Pakistan as supporting Play developer
and merchant registration. Registration and payout setup still require correct
identity, legal address, supported payment/bank details, and tax information.
Citizenship and a temporary location do not by themselves choose an account's
legal country. Worldwide customer reach and USD goal tracking are separate
from the payout currency and taxes. Do not register under an invented country
or assume a USD price is the right local price everywhere.

Official references: [supported locations](https://support.google.com/googleplay/android-developer/answer/9306917),
[service fees](https://support.google.com/googleplay/android-developer/answer/112622),
and [15% tier enrollment](https://support.google.com/googleplay/android-developer/answer/10632485).
Recheck them before implementing billing or opening a payments profile.

A website can later host downloads, privacy policy, help, and a changelog. An
iOS edition requires its own implementation, Apple signing, and iPhone testing.
Those are separate projects after Android feedback; neither is being published
as part of this beta.

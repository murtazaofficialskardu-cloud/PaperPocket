package com.paperpocket.app;

/** Sharing intentions, not promises about any service's attachment limit. */
public enum PdfSharePreset {
    CHAT("WhatsApp / chat", PdfConverter.Profile.COMPACT,
            "Smaller attachments at Compact quality. Check small text before saving."),
    EMAIL("Email / everyday", PdfConverter.Profile.BALANCED,
            "Balanced quality for readable everyday documents and email attachments."),
    FINE_PRINT("Fine print / forms", PdfConverter.Profile.HIGH,
            "More detail for small print. The result may be larger than your original.");

    public final String label;
    public final PdfConverter.Profile profile;
    public final String hint;

    PdfSharePreset(String label, PdfConverter.Profile profile, String hint) {
        this.label = label;
        this.profile = profile;
        this.hint = hint;
    }
}

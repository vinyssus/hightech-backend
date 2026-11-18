package cm.hitechdz.auth.domain.content;

/** Emplacements de la bannière d'accueil, avec leurs normes d'image. */
public enum BannerSlot {
    MAIN("16:9", 16, 9, 1200, 675, "1600x900"),
    SIDE_TOP("4:3", 4, 3, 600, 450, "800x600"),
    SIDE_BOTTOM("4:3", 4, 3, 600, 450, "800x600");

    private final String ratioLabel;
    private final int ratioW;
    private final int ratioH;
    private final int minWidth;
    private final int minHeight;
    private final String recommended;

    BannerSlot(String ratioLabel, int ratioW, int ratioH, int minWidth, int minHeight, String recommended) {
        this.ratioLabel = ratioLabel;
        this.ratioW = ratioW;
        this.ratioH = ratioH;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.recommended = recommended;
    }

    public String ratioLabel() { return ratioLabel; }
    public double ratio() { return (double) ratioW / ratioH; }
    public int minWidth() { return minWidth; }
    public int minHeight() { return minHeight; }
    public String recommended() { return recommended; }

    /** Sous-dossier Cloudinary : main, side-top, side-bottom. */
    public String folderName() { return name().toLowerCase().replace('_', '-'); }
}

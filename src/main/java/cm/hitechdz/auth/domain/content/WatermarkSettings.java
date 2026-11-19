package cm.hitechdz.auth.domain.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Réglages du logo incrusté sur les photos produits (ligne unique id = 1). */
@Entity
@Table(name = "watermark_settings")
public class WatermarkSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "logo_public_id")
    private String logoPublicId;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "custom_logo", nullable = false)
    private boolean customLogo;

    @Column(nullable = false, length = 20)
    private String position = "SOUTH_EAST";

    @Column(name = "width_percent", nullable = false)
    private int widthPercent = 33;

    @Column(name = "margin_percent", nullable = false)
    private int marginPercent = 3;

    @Column(nullable = false)
    private int opacity = 100;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Integer getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLogoPublicId() { return logoPublicId; }
    public void setLogoPublicId(String logoPublicId) { this.logoPublicId = logoPublicId; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public boolean isCustomLogo() { return customLogo; }
    public void setCustomLogo(boolean customLogo) { this.customLogo = customLogo; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public int getWidthPercent() { return widthPercent; }
    public void setWidthPercent(int widthPercent) { this.widthPercent = widthPercent; }
    public int getMarginPercent() { return marginPercent; }
    public void setMarginPercent(int marginPercent) { this.marginPercent = marginPercent; }
    public int getOpacity() { return opacity; }
    public void setOpacity(int opacity) { this.opacity = opacity; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}

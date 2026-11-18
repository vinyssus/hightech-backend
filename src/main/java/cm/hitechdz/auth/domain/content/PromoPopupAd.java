package cm.hitechdz.auth.domain.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Publicité verticale (9:16) du panneau flottant : image animée + lien, sans texte affiché. */
@Entity
@Table(name = "promo_popup_ads")
public class PromoPopupAd {

    public enum Status { LIVE, SCHEDULED, EXPIRED, DISABLED }

    @Id
    private UUID id;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "image_public_id")
    private String imagePublicId;

    @Column(name = "mobile_image_url", length = 500)
    private String mobileImageUrl;

    @Column(name = "mobile_image_public_id")
    private String mobileImagePublicId;

    @Column(name = "alt_text", nullable = false, length = 120)
    private String altText;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20)
    private PromoLinkType linkType = PromoLinkType.NONE;

    @Column(name = "link_value", length = 500)
    private String linkValue;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 10)
    private String format;

    private Integer frames;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Status statusAt(Instant now) {
        if (!active) return Status.DISABLED;
        if (endsAt != null && now.isAfter(endsAt)) return Status.EXPIRED;
        if (startsAt != null && now.isBefore(startsAt)) return Status.SCHEDULED;
        return Status.LIVE;
    }

    public UUID getId() { return id; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getImagePublicId() { return imagePublicId; }
    public void setImagePublicId(String imagePublicId) { this.imagePublicId = imagePublicId; }
    public String getMobileImageUrl() { return mobileImageUrl; }
    public void setMobileImageUrl(String mobileImageUrl) { this.mobileImageUrl = mobileImageUrl; }
    public String getMobileImagePublicId() { return mobileImagePublicId; }
    public void setMobileImagePublicId(String mobileImagePublicId) { this.mobileImagePublicId = mobileImagePublicId; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public PromoLinkType getLinkType() { return linkType; }
    public void setLinkType(PromoLinkType linkType) { this.linkType = linkType; }
    public String getLinkValue() { return linkValue; }
    public void setLinkValue(String linkValue) { this.linkValue = linkValue; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public Integer getFrames() { return frames; }
    public void setFrames(Integer frames) { this.frames = frames; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

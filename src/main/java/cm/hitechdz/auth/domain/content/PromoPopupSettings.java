package cm.hitechdz.auth.domain.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Réglages du panneau publicitaire (ligne unique id = 1). */
@Entity
@Table(name = "promo_popup_settings")
public class PromoPopupSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "delay_ms", nullable = false)
    private int delayMs = 1500;

    @Column(name = "interval_ms", nullable = false)
    private int intervalMs = 4000;

    @Column(name = "show_on_mobile", nullable = false)
    private boolean showOnMobile = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "dismiss_mode", nullable = false, length = 20)
    private DismissMode dismissMode = DismissMode.SESSION;

    /** Dernière modification du panneau : réglages ou publicités. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Integer getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
    public int getIntervalMs() { return intervalMs; }
    public void setIntervalMs(int intervalMs) { this.intervalMs = intervalMs; }
    public boolean isShowOnMobile() { return showOnMobile; }
    public void setShowOnMobile(boolean showOnMobile) { this.showOnMobile = showOnMobile; }
    public DismissMode getDismissMode() { return dismissMode; }
    public void setDismissMode(DismissMode dismissMode) { this.dismissMode = dismissMode; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}

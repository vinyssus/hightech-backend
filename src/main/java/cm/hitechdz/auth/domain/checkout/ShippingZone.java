package cm.hitechdz.auth.domain.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Zone de livraison gérée dans l'admin. Seules les zones actives sont proposées aux clients. */
@Entity
@Table(name = "shipping_zones")
public class ShippingZone {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String label;

    /** Frais en FCFA. */
    @Column(nullable = false)
    private long fee;

    @Column(length = 120)
    private String delay;

    /** Zone « autres villes » : la ville réelle est obligatoire. */
    @Column(name = "requires_city", nullable = false)
    private boolean requiresCity;

    /** Retrait en boutique : adresse facultative. */
    @Column(nullable = false)
    private boolean pickup;

    @Column(name = "cod_allowed", nullable = false)
    private boolean codAllowed = true;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public long getFee() { return fee; }
    public void setFee(long fee) { this.fee = fee; }

    public String getDelay() { return delay; }
    public void setDelay(String delay) { this.delay = delay; }

    public boolean isRequiresCity() { return requiresCity; }
    public void setRequiresCity(boolean requiresCity) { this.requiresCity = requiresCity; }

    public boolean isPickup() { return pickup; }
    public void setPickup(boolean pickup) { this.pickup = pickup; }

    public boolean isCodAllowed() { return codAllowed; }
    public void setCodAllowed(boolean codAllowed) { this.codAllowed = codAllowed; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public Instant getUpdatedAt() { return updatedAt; }
}

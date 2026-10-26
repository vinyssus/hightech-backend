package cm.hitechdz.auth.domain.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Devis : montants calculés par le serveur pour un panier, une zone et un code promo. */
@Entity
@Table(name = "checkout_quotes")
public class CheckoutQuote {

    @Id
    @Column(length = 48)
    private String id;

    /** Empreinte des articles, de la zone, de la ville et du code promo. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "zone_id", nullable = false, length = 40)
    private String zoneId;

    @Column(name = "promo_code", length = 40)
    private String promoCode;

    @Column(nullable = false) private long subtotal;
    @Column(nullable = false) private long discount;
    @Column(nullable = false) private long shipping;
    @Column(nullable = false) private long total;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected CheckoutQuote() {}

    public CheckoutQuote(String id, String requestHash, UUID userId, String zoneId, String promoCode,
                         long subtotal, long discount, long shipping, long total, Instant expiresAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.userId = userId;
        this.zoneId = zoneId;
        this.promoCode = promoCode;
        this.subtotal = subtotal;
        this.discount = discount;
        this.shipping = shipping;
        this.total = total;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public UUID getUserId() { return userId; }
    public String getZoneId() { return zoneId; }
    public String getPromoCode() { return promoCode; }
    public long getSubtotal() { return subtotal; }
    public long getDiscount() { return discount; }
    public long getShipping() { return shipping; }
    public long getTotal() { return total; }
    public Instant getExpiresAt() { return expiresAt; }
}

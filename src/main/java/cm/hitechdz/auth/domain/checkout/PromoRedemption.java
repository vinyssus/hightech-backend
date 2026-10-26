package cm.hitechdz.auth.domain.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Utilisation d'un code promo par une commande (customerKey = compte ou téléphone de l'invité). */
@Entity
@Table(name = "promo_redemptions")
public class PromoRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promo_id", nullable = false)
    private Long promoId;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "customer_key", nullable = false, length = 120)
    private String customerKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PromoRedemption() {}

    public PromoRedemption(Long promoId, UUID orderId, UUID userId, String customerKey) {
        this.promoId = promoId;
        this.orderId = orderId;
        this.userId = userId;
        this.customerKey = customerKey;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getPromoId() { return promoId; }
    public UUID getOrderId() { return orderId; }
}

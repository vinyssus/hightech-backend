package cm.hitechdz.auth.domain.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Réglages de vente (une seule ligne, id = 1). */
@Entity
@Table(name = "shop_settings")
public class ShopSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;

    /** Livraison offerte à partir de ce sous-total (après remise) ; null = jamais. */
    @Column(name = "free_shipping_from")
    private Long freeShippingFrom;

    @Column(name = "quote_validity_minutes", nullable = false)
    private int quoteValidityMinutes = 15;

    /** Durée de réservation du stock d'une commande à payer en ligne. */
    @Column(name = "reservation_minutes", nullable = false)
    private int reservationMinutes = 30;

    /** Délai laissé au client pour valider un paiement sur son téléphone. */
    @Column(name = "payment_timeout_minutes", nullable = false)
    private int paymentTimeoutMinutes = 5;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getFreeShippingFrom() { return freeShippingFrom; }
    public void setFreeShippingFrom(Long freeShippingFrom) { this.freeShippingFrom = freeShippingFrom; }

    public int getQuoteValidityMinutes() { return quoteValidityMinutes; }
    public void setQuoteValidityMinutes(int v) { this.quoteValidityMinutes = v; }

    public int getReservationMinutes() { return reservationMinutes; }
    public void setReservationMinutes(int v) { this.reservationMinutes = v; }

    public int getPaymentTimeoutMinutes() { return paymentTimeoutMinutes; }
    public void setPaymentTimeoutMinutes(int v) { this.paymentTimeoutMinutes = v; }

    public Instant getUpdatedAt() { return updatedAt; }
}

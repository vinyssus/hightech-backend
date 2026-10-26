package cm.hitechdz.auth.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Notification déjà traitée (anti-rejeu). */
@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentWebhookEvent() {}

    public PaymentWebhookEvent(String provider, String eventKey) {
        this.provider = provider;
        this.eventKey = eventKey;
    }

    @PrePersist
    void onCreate() {
        receivedAt = Instant.now();
    }
}

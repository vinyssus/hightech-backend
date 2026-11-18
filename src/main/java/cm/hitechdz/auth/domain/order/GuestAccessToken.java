package cm.hitechdz.auth.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;

/** Jeton de suivi supplémentaire d'une commande (haché), délivré par numéro + téléphone. */
@Embeddable
public class GuestAccessToken {

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GuestAccessToken() {}

    public GuestAccessToken(String tokenHash, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}

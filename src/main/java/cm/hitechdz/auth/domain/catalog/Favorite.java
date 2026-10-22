package cm.hitechdz.auth.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "favorites")
public class Favorite {

    @EmbeddedId
    private Key id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Favorite() {}

    public Favorite(UUID userId, String productId) {
        this.id = new Key(userId, productId);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Key getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "user_id")
        private UUID userId;

        @Column(name = "product_id", length = 36)
        private String productId;

        protected Key() {}

        public Key(UUID userId, String productId) {
            this.userId = userId;
            this.productId = productId;
        }

        public UUID getUserId() { return userId; }
        public String getProductId() { return productId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(userId, k.userId) && Objects.equals(productId, k.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, productId);
        }
    }
}

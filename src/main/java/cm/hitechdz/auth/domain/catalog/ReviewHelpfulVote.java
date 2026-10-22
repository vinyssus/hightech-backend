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

/** Vote « avis utile » : un seul par client et par avis (clé primaire composite). */
@Entity
@Table(name = "review_helpful_votes")
public class ReviewHelpfulVote {

    @EmbeddedId
    private Key id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReviewHelpfulVote() {}

    public ReviewHelpfulVote(Long reviewId, UUID userId) {
        this.id = new Key(reviewId, userId);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Key getId() { return id; }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "review_id")
        private Long reviewId;

        @Column(name = "user_id")
        private UUID userId;

        protected Key() {}

        public Key(Long reviewId, UUID userId) {
            this.reviewId = reviewId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(reviewId, k.reviewId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reviewId, userId);
        }
    }
}

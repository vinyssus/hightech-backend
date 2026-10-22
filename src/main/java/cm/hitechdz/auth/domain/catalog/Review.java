package cm.hitechdz.auth.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Avis client. Le prénom et la ville de l'auteur sont copiés à la création :
 * l'avis reste lisible même si le compte est supprimé (user_id passe alors à NULL).
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "author_name", nullable = false, length = 40)
    private String authorName;

    @Column(name = "author_city", length = 60)
    private String authorCity;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 3000)
    private String body;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "helpful_count", nullable = false)
    private int helpfulCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorCity() { return authorCity; }
    public void setAuthorCity(String authorCity) { this.authorCity = authorCity; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public int getHelpfulCount() { return helpfulCount; }
    /** Utilisé par le seed uniquement (en temps normal : incrément en base). */
    public void setHelpfulCount(int helpfulCount) { this.helpfulCount = helpfulCount; }

    public Instant getCreatedAt() { return createdAt; }
    /** Utilisé par le seed uniquement. */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

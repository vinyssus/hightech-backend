package cm.hitechdz.auth.domain.catalog;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "categories")
public class Category {

    public static final Set<String> ICONS = Set.of("laptop", "smartphone", "headphones", "router", "tv", "harddrive");
    public static final String DEFAULT_ICON = "tv";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Généré par le serveur à la création, jamais modifié ensuite. */
    @Column(nullable = false, unique = true, length = 120, updatable = false)
    private String slug;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 20)
    private String icon = DEFAULT_ICON;

    @Column(nullable = false)
    private int position;

    @ElementCollection
    @CollectionTable(name = "category_subs", joinColumns = @JoinColumn(name = "category_id"))
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false, length = 80)
    private List<String> sub = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public List<String> getSub() { return sub; }
    public void setSub(List<String> sub) {
        this.sub.clear();
        if (sub != null) {
            this.sub.addAll(sub);
        }
    }

    public Instant getCreatedAt() { return createdAt; }
}

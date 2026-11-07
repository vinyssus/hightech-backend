package cm.hitechdz.auth.domain.catalog;

import cm.hitechdz.auth.util.SlugUtil;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<Subcategory> subcategories = new ArrayList<>();

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

    public List<Subcategory> getSubcategories() { return subcategories; }

    /** Noms des sous-catégories dans l'ordre (ancien champ « sub », conservé pour compatibilité). */
    public List<String> getSub() {
        return subcategories.stream().map(Subcategory::getName).toList();
    }

    public Optional<Subcategory> findSubcategory(String slug) {
        return slug == null ? Optional.empty()
                : subcategories.stream().filter(s -> s.getSlug().equals(slug)).findFirst();
    }

    /** Recherche par slug, ou par nom converti en slug (« PC Gaming » → pc-gaming). */
    public Optional<Subcategory> resolveSubcategory(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return findSubcategory(value.trim()).or(() -> findSubcategory(SlugUtil.slugify(value)));
    }

    public Subcategory addSubcategory(String slug, String name) {
        int next = subcategories.stream().mapToInt(Subcategory::getPosition).max().orElse(-1) + 1;
        Subcategory s = new Subcategory(this, slug, name, next);
        subcategories.add(s);
        return s;
    }

    /**
     * Synchronise la liste à partir de noms (ancien format « sub ») : les sous-catégories existantes
     * de même slug sont conservées (renommées si la casse change), les nouvelles créées, les absentes retirées.
     */
    public void setSub(List<String> names) {
        List<String> clean = names == null ? List.of() : names.stream()
                .filter(n -> n != null && !n.isBlank()).map(String::trim).toList();
        List<String> slugs = new ArrayList<>();
        int position = 0;
        for (String name : clean) {
            String slug = SlugUtil.slugify(name);
            if (slug.isEmpty() || slugs.contains(slug)) {
                continue;
            }
            slugs.add(slug);
            Subcategory existing = findSubcategory(slug).orElse(null);
            if (existing == null) {
                existing = new Subcategory(this, slug, name, position);
                subcategories.add(existing);
            } else {
                existing.setName(name);
                existing.setPosition(position);
            }
            position++;
        }
        subcategories.removeIf(s -> !slugs.contains(s.getSlug()));
        subcategories.sort(Comparator.comparingInt(Subcategory::getPosition));
    }

    public Instant getCreatedAt() { return createdAt; }
}

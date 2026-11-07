package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.Subcategory;
import cm.hitechdz.auth.dto.catalog.AdminCategoryDto;
import cm.hitechdz.auth.dto.catalog.AdminSubcategoryDto;
import cm.hitechdz.auth.dto.catalog.CategoryDto;
import cm.hitechdz.auth.dto.catalog.CategoryRequest;
import cm.hitechdz.auth.dto.catalog.SubcategoryRequest;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.CategoryRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CategoryService {

    static final String NOT_FOUND = "Catégorie introuvable.";
    static final String ALREADY_EXISTS = "Cette catégorie existe déjà.";
    static final String EMPTY_SLUG = "Le nom de la catégorie doit contenir au moins une lettre ou un chiffre.";
    static final String BAD_ICON = "Icône invalide (laptop, smartphone, headphones, router, tv ou harddrive).";
    static final String SUB_NOT_FOUND = "Sous-catégorie introuvable.";
    static final String SUB_EXISTS = "Cette sous-catégorie existe déjà dans cette catégorie.";
    static final String SUB_EMPTY_SLUG = "Le nom de la sous-catégorie doit contenir au moins une lettre ou un chiffre.";
    static final int MAX_SUBS = 30;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> publicList() {
        return categoryRepository.findAllOrdered().stream().map(CatalogMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> adminList() {
        return categoryRepository.findAllOrdered().stream().map(this::adminDto).toList();
    }

    @Transactional
    public AdminCategoryDto create(CategoryRequest req) {
        String slug = SlugUtil.slugify(req.name());
        if (slug.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, EMPTY_SLUG, Map.of("name", EMPTY_SLUG));
        }
        if (categoryRepository.existsBySlug(slug)) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_EXISTS, Map.of("name", ALREADY_EXISTS));
        }
        Category c = new Category();
        c.setSlug(slug);
        c.setName(req.name().trim());
        c.setIcon(resolveIcon(req.icon(), Category.DEFAULT_ICON));
        c.setSub(req.sub());
        c.setPosition(categoryRepository.maxPosition() + 1);
        categoryRepository.saveAndFlush(c);
        return adminDto(c);
    }

    /**
     * Renommer une catégorie ne change jamais son slug. Si « sub » est envoyé, la liste est synchronisée
     * par nom ; retirer une sous-catégorie utilisée par des produits est refusé (409).
     */
    @Transactional
    public AdminCategoryDto update(String slug, CategoryRequest req) {
        Category c = load(slug);
        c.setName(req.name().trim());
        if (req.icon() != null) {
            c.setIcon(resolveIcon(req.icon(), c.getIcon()));
        }
        if (req.sub() != null) {
            Set<String> kept = new HashSet<>();
            req.sub().forEach(n -> kept.add(SlugUtil.slugify(n)));
            for (Subcategory s : c.getSubcategories()) {
                if (!kept.contains(s.getSlug())) {
                    ensureUnused(s);
                }
            }
            c.setSub(req.sub());
        }
        categoryRepository.flush();
        // Le texte de recherche des produits contient le nom de la catégorie et de la sous-catégorie
        productRepository.findByCategory(c).forEach(SearchText::refresh);
        return adminDto(c);
    }

    @Transactional
    public void delete(String slug) {
        Category c = load(slug);
        long used = productRepository.countByCategoryAndDeletedAtIsNull(c);
        if (used > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Impossible de supprimer : " + used + " produit(s) utilisent cette catégorie.");
        }
        // Les produits supprimés logiquement gardent leur historique (category_id passe à NULL).
        for (Product p : productRepository.findByCategory(c)) {
            p.setSubcategory(null);
        }
        categoryRepository.delete(c);
    }

    // ------------------------------------------------------------------ sous-catégories
    @Transactional
    public AdminSubcategoryDto createSubcategory(String categorySlug, SubcategoryRequest req) {
        Category c = load(categorySlug);
        String name = req.name().trim();
        String slug = SlugUtil.slugify(name);
        if (slug.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SUB_EMPTY_SLUG, Map.of("name", SUB_EMPTY_SLUG));
        }
        if (c.findSubcategory(slug).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, SUB_EXISTS, Map.of("name", SUB_EXISTS));
        }
        if (c.getSubcategories().size() >= MAX_SUBS) {
            String msg = MAX_SUBS + " sous-catégories maximum par catégorie.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("name", msg));
        }
        Subcategory s = c.addSubcategory(slug, name);
        if (req.position() != null) {
            move(c, s, req.position());
        }
        categoryRepository.flush();
        return CatalogMapper.toAdminDto(s, 0);
    }

    /** Renommer ne change pas le slug : les liens et filtres existants restent valides. */
    @Transactional
    public AdminSubcategoryDto updateSubcategory(String categorySlug, String subSlug, SubcategoryRequest req) {
        Category c = load(categorySlug);
        Subcategory s = loadSub(c, subSlug);
        String name = req.name().trim();
        if (SlugUtil.slugify(name).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SUB_EMPTY_SLUG, Map.of("name", SUB_EMPTY_SLUG));
        }
        boolean nameTaken = c.getSubcategories().stream()
                .anyMatch(o -> o != s && o.getName().equalsIgnoreCase(name));
        if (nameTaken) {
            throw new ApiException(HttpStatus.CONFLICT, SUB_EXISTS, Map.of("name", SUB_EXISTS));
        }
        s.setName(name);
        if (req.position() != null) {
            move(c, s, req.position());
        }
        categoryRepository.flush();
        productRepository.findBySubcategory(s).forEach(SearchText::refresh);
        return CatalogMapper.toAdminDto(s, productRepository.countBySubcategoryAndDeletedAtIsNull(s));
    }

    @Transactional
    public AdminCategoryDto reorderSubcategories(String categorySlug, List<String> slugs) {
        Category c = load(categorySlug);
        Set<String> current = new HashSet<>();
        c.getSubcategories().forEach(s -> current.add(s.getSlug()));
        if (slugs.size() != current.size() || !current.equals(new HashSet<>(slugs))) {
            String msg = "Envoyez chaque sous-catégorie de la catégorie exactement une fois.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("slugs", msg));
        }
        for (int i = 0; i < slugs.size(); i++) {
            c.findSubcategory(slugs.get(i)).orElseThrow().setPosition(i);
        }
        c.getSubcategories().sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        categoryRepository.flush();
        return adminDto(c);
    }

    @Transactional
    public void deleteSubcategory(String categorySlug, String subSlug) {
        Category c = load(categorySlug);
        Subcategory s = loadSub(c, subSlug);
        ensureUnused(s);
        // Produits supprimés logiquement : on détache simplement la sous-catégorie
        productRepository.findBySubcategory(s).forEach(p -> p.setSubcategory(null));
        c.getSubcategories().remove(s);
        renumber(c);
        categoryRepository.flush();
    }

    /** Recherche par slug, ou par nom converti en slug (« Ordinateurs » → ordinateurs). */
    @Transactional(readOnly = true)
    public Category findForProduct(String value) {
        if (value == null) {
            return null;
        }
        return categoryRepository.findBySlug(value.trim())
                .or(() -> categoryRepository.findBySlug(SlugUtil.slugify(value)))
                .orElse(null);
    }

    // ------------------------------------------------------------------ helpers
    private AdminCategoryDto adminDto(Category c) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : productRepository.countBySubcategoryIn(c)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return CatalogMapper.toAdminDto(c, productRepository.countByCategoryAndDeletedAtIsNull(c), counts);
    }

    private void ensureUnused(Subcategory s) {
        long used = productRepository.countBySubcategoryAndDeletedAtIsNull(s);
        if (used > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Impossible de supprimer « " + s.getName() + " » : "
                    + used + " produit(s) l'utilisent. Changez d'abord leur sous-catégorie.");
        }
    }

    private static void move(Category c, Subcategory s, int position) {
        List<Subcategory> list = new ArrayList<>(c.getSubcategories());
        list.remove(s);
        list.add(Math.min(position, list.size()), s);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
        c.getSubcategories().sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
    }

    private static void renumber(Category c) {
        for (int i = 0; i < c.getSubcategories().size(); i++) {
            c.getSubcategories().get(i).setPosition(i);
        }
    }

    private Category load(String slug) {
        return categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static Subcategory loadSub(Category c, String slug) {
        return c.findSubcategory(slug).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, SUB_NOT_FOUND));
    }

    private static String resolveIcon(String icon, String fallback) {
        if (icon == null || icon.isBlank()) {
            return fallback;
        }
        String value = icon.trim().toLowerCase(Locale.ROOT);
        if (!Category.ICONS.contains(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, BAD_ICON, Map.of("icon", BAD_ICON));
        }
        return value;
    }
}

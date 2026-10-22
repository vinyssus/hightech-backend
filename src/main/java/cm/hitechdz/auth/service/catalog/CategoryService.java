package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.dto.catalog.AdminCategoryDto;
import cm.hitechdz.auth.dto.catalog.CategoryDto;
import cm.hitechdz.auth.dto.catalog.CategoryRequest;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.CategoryRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CategoryService {

    static final String NOT_FOUND = "Catégorie introuvable.";
    static final String ALREADY_EXISTS = "Cette catégorie existe déjà.";
    static final String EMPTY_SLUG = "Le nom de la catégorie doit contenir au moins une lettre ou un chiffre.";
    static final String BAD_ICON = "Icône invalide (laptop, smartphone, headphones, router, tv ou harddrive).";

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
        return categoryRepository.findAllOrdered().stream()
                .map(c -> CatalogMapper.toAdminDto(c, productRepository.countByCategoryAndDeletedAtIsNull(c)))
                .toList();
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
        c.setSub(cleanSubs(req.sub()));
        c.setPosition(categoryRepository.maxPosition() + 1);
        categoryRepository.saveAndFlush(c);
        return CatalogMapper.toAdminDto(c, 0);
    }

    /** Renommer une catégorie ne change jamais son slug. */
    @Transactional
    public AdminCategoryDto update(String slug, CategoryRequest req) {
        Category c = load(slug);
        c.setName(req.name().trim());
        if (req.icon() != null) {
            c.setIcon(resolveIcon(req.icon(), c.getIcon()));
        }
        if (req.sub() != null) {
            c.setSub(cleanSubs(req.sub()));
        }
        // Le texte de recherche des produits contient le nom de la catégorie et ses sous-catégories
        productRepository.findByCategory(c).forEach(SearchText::refresh);
        return CatalogMapper.toAdminDto(c, productRepository.countByCategoryAndDeletedAtIsNull(c));
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
        categoryRepository.delete(c);
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

    private Category load(String slug) {
        return categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
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

    private static List<String> cleanSubs(List<String> subs) {
        return subs == null ? List.of() : subs.stream().map(String::trim).distinct().toList();
    }
}

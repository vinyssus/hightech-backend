package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Favorite;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.dto.catalog.ProductSummary;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.catalog.FavoriteRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;

    public FavoriteService(FavoriteRepository favoriteRepository, ProductRepository productRepository) {
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
    }

    /** Favoris visibles (publiés, non supprimés), du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<ProductSummary> list(UUID userId) {
        List<String> ids = favoriteRepository.findProductIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Product> visible = productRepository.findVisibleByIds(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return ids.stream().map(visible::get).filter(Objects::nonNull).map(CatalogMapper::toSummary).toList();
    }

    /** Idempotent : ajouter deux fois le même produit ne change rien. */
    @Transactional
    public void add(UUID userId, String productId) {
        if (productRepository.findByIdAndDeletedAtIsNull(productId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, CatalogQueryService.PRODUCT_NOT_FOUND);
        }
        Favorite.Key key = new Favorite.Key(userId, productId);
        if (!favoriteRepository.existsById(key)) {
            favoriteRepository.save(new Favorite(userId, productId));
        }
    }

    @Transactional
    public void remove(UUID userId, String productId) {
        favoriteRepository.deleteById(new Favorite.Key(userId, productId));
    }

    /** Fusionne les favoris enregistrés sans compte ; les produits inconnus sont ignorés. */
    @Transactional
    public List<ProductSummary> merge(UUID userId, List<String> productIds) {
        LinkedHashSet<String> unique = productIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unique.isEmpty()) {
            for (Product p : productRepository.findVisibleByIds(unique)) {
                Favorite.Key key = new Favorite.Key(userId, p.getId());
                if (!favoriteRepository.existsById(key)) {
                    favoriteRepository.save(new Favorite(userId, p.getId()));
                }
            }
            favoriteRepository.flush();
        }
        return list(userId);
    }
}

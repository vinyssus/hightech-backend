package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Product;
import org.springframework.stereotype.Component;

/** Accès contrôlé aux règles internes du catalogue pour le seed de démonstration. */
@Component
public class CatalogSeedSupport {

    private final ReviewService reviewService;

    public CatalogSeedSupport(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public void refreshSearchText(Product product) {
        SearchText.refresh(product);
    }

    public void recomputeRating(Product product) {
        reviewService.recomputeRating(product);
    }
}

package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    boolean existsByPublicId(String publicId);
}

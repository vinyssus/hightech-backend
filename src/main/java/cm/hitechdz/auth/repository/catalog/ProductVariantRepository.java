package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    Optional<ProductVariant> findBySku(String sku);

    /** SKU déjà utilisés par d'autres produits. */
    @Query("select v.sku from ProductVariant v where v.sku in :skus and v.product.id <> :productId")
    List<String> findSkusUsedElsewhere(@Param("skus") Collection<String> skus, @Param("productId") String productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.product.id = :productId order by v.position asc, v.id asc")
    List<ProductVariant> findByProductIdForUpdate(@Param("productId") String productId);
}

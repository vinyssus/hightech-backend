package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndDeletedAtIsNull(String id);

    Optional<Product> findBySlugAndDeletedAtIsNull(String slug);

    @Query("select p from Product p where p.slug = :slug and p.status = :status and p.deletedAt is null")
    Optional<Product> findVisibleBySlug(@Param("slug") String slug, @Param("status") ProductStatus status);

    boolean existsBySlug(String slug);

    long countByCategoryAndDeletedAtIsNull(Category category);

    long countByBrandAndDeletedAtIsNull(Brand brand);

    List<Product> findByCategory(Category category);

    List<Product> findByBrand(Brand brand);

    /** Verrou pessimiste (SELECT ... FOR UPDATE) pour la réservation de stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") String id);

    @Query("""
            select p from Product p
            where p.status = cm.hitechdz.auth.domain.catalog.ProductStatus.PUBLISHED and p.deletedAt is null
              and p.oldPrice is not null and p.oldPrice > p.price
            order by ((p.oldPrice - p.price) * 1.0 / p.oldPrice) desc, p.id asc
            """)
    List<Product> findFlash(Pageable pageable);

    @Query("""
            select p from Product p
            where p.status = cm.hitechdz.auth.domain.catalog.ProductStatus.PUBLISHED and p.deletedAt is null
              and p.newProduct = true
            order by p.createdAt desc, p.id asc
            """)
    List<Product> findNewArrivals(Pageable pageable);

    @Query("""
            select p from Product p
            where p.status = cm.hitechdz.auth.domain.catalog.ProductStatus.PUBLISHED and p.deletedAt is null
            order by p.sold desc, p.rating desc, p.id asc
            """)
    List<Product> findBestSellers(Pageable pageable);

    @Query("""
            select p from Product p
            where p.deletedAt is null and p.stock <= :threshold
            order by p.stock asc, p.name asc
            """)
    List<Product> findLowStock(@Param("threshold") int threshold);

    @Query("""
            select p from Product p
            where p.id in :ids and p.status = cm.hitechdz.auth.domain.catalog.ProductStatus.PUBLISHED
              and p.deletedAt is null
            """)
    List<Product> findVisibleByIds(@Param("ids") Collection<String> ids);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = p.sold + :qty where p.id = :id")
    int incrementSold(@Param("id") String id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.sold = case when p.sold >= :qty then p.sold - :qty else 0 end where p.id = :id")
    int decrementSold(@Param("id") String id, @Param("qty") int qty);
}

package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductId(String productId, Pageable pageable);

    @EntityGraph(attributePaths = "product")
    @Query("select r from Review r")
    Page<Review> findAllWithProduct(Pageable pageable);

    @EntityGraph(attributePaths = "product")
    @Query("select r from Review r where r.product.id = :productId")
    Page<Review> findByProductIdWithProduct(@Param("productId") String productId, Pageable pageable);

    boolean existsByProductIdAndUserId(String productId, UUID userId);

    /** [note, nombre] pour la distribution des notes. */
    @Query("select r.rating, count(r) from Review r where r.product.id = :productId group by r.rating")
    List<Object[]> ratingDistribution(@Param("productId") String productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Review r set r.helpfulCount = r.helpfulCount + 1 where r.id = :id")
    int incrementHelpful(@Param("id") Long id);
}

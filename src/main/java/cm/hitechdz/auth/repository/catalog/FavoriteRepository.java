package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<Favorite, Favorite.Key> {

    @Query("select f.id.productId from Favorite f where f.id.userId = :userId order by f.createdAt desc")
    List<String> findProductIds(@Param("userId") UUID userId);
}

package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findBySlug(String slug);

    Optional<Brand> findByNameKey(String nameKey);

    boolean existsBySlug(String slug);

    @Query("select b from Brand b order by b.name asc")
    List<Brand> findAllOrdered();
}

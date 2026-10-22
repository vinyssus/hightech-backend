package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("select c from Category c order by c.position asc, c.id asc")
    List<Category> findAllOrdered();

    @Query("select coalesce(max(c.position), -1) from Category c")
    int maxPosition();
}

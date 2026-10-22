package cm.hitechdz.auth.repository.catalog;

import cm.hitechdz.auth.domain.catalog.Brand;
import cm.hitechdz.auth.domain.catalog.Category;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recherche du catalogue public (Criteria API) : page de produits, comptes par marque
 * et bornes de prix, avec exactement les mêmes filtres.
 */
@Repository
public class ProductSearchRepository {

    public enum SortMode { PERTINENCE, PRICE_ASC, PRICE_DESC, NEWEST, POPULARITY }

    public record PriceRange(long min, long max) {}

    private static final char ESCAPE = '\\';

    @PersistenceContext
    private EntityManager em;

    public Page<Product> search(ProductFilter filter, SortMode sort, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // Données
        CriteriaQuery<Product> query = cb.createQuery(Product.class);
        Root<Product> root = query.from(Product.class);
        Join<Product, Brand> brand = root.join("brand", JoinType.LEFT);
        Join<Product, Category> category = root.join("category", JoinType.LEFT);
        query.select(root).where(predicates(cb, root, brand, category, filter));
        query.orderBy(orders(cb, root, filter, sort));

        List<Product> content = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // Total
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Product> countRoot = countQuery.from(Product.class);
        Join<Product, Brand> countBrand = countRoot.join("brand", JoinType.LEFT);
        Join<Product, Category> countCategory = countRoot.join("category", JoinType.LEFT);
        countQuery.select(cb.count(countRoot)).where(predicates(cb, countRoot, countBrand, countCategory, filter));
        long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /** Nombre de produits par marque (clé : name_key). Appeler avec un filtre sans marques. */
    public Map<String, Long> countByBrand(ProductFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Product> root = query.from(Product.class);
        Join<Product, Brand> brand = root.join("brand", JoinType.INNER);
        Join<Product, Category> category = root.join("category", JoinType.LEFT);
        Expression<String> key = brand.get("nameKey");
        Expression<Long> count = cb.count(root);
        query.multiselect(key, count)
                .where(predicates(cb, root, brand, category, filter))
                .groupBy(key);

        Map<String, Long> result = new LinkedHashMap<>();
        for (Tuple t : em.createQuery(query).getResultList()) {
            result.put(t.get(key), t.get(count));
        }
        return result;
    }

    /** Bornes de prix des produits filtrés. Appeler avec un filtre sans min/max. */
    public PriceRange priceRange(ProductFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Product> root = query.from(Product.class);
        Join<Product, Brand> brand = root.join("brand", JoinType.LEFT);
        Join<Product, Category> category = root.join("category", JoinType.LEFT);
        Expression<Long> min = cb.min(root.<Long>get("price"));
        Expression<Long> max = cb.max(root.<Long>get("price"));
        query.multiselect(min, max).where(predicates(cb, root, brand, category, filter));
        Tuple t = em.createQuery(query).getSingleResult();
        Long lo = t.get(min);
        Long hi = t.get(max);
        return new PriceRange(lo == null ? 0 : lo, hi == null ? 0 : hi);
    }

    // ------------------------------------------------------------------ construction
    private Predicate[] predicates(CriteriaBuilder cb, Root<Product> root,
                                   Join<Product, Brand> brand, Join<Product, Category> category,
                                   ProductFilter f) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.equal(root.get("status"), ProductStatus.PUBLISHED));
        ps.add(cb.isNull(root.get("deletedAt")));

        if (f.hasQuery()) {
            Expression<String> text = root.get("searchText");
            for (String token : f.q().trim().split("\\s+")) {
                ps.add(cb.like(text, "%" + escapeLike(token) + "%", ESCAPE));
            }
        }
        if (f.category() != null && !f.category().isBlank()) {
            ps.add(cb.equal(category.get("slug"), f.category()));
        }
        if (f.brands() != null && !f.brands().isEmpty()) {
            ps.add(brand.get("nameKey").in(f.brands()));
        }
        if (f.min() != null) {
            ps.add(cb.greaterThanOrEqualTo(root.<Long>get("price"), f.min()));
        }
        if (f.max() != null) {
            ps.add(cb.lessThanOrEqualTo(root.<Long>get("price"), f.max()));
        }
        if (f.promo()) {
            ps.add(cb.isNotNull(root.get("oldPrice")));
            ps.add(cb.greaterThan(root.<Long>get("oldPrice"), root.<Long>get("price")));
        }
        if (f.rating() != null) {
            ps.add(cb.greaterThanOrEqualTo(root.<Double>get("rating"), f.rating()));
        }
        if (f.inStock()) {
            ps.add(cb.greaterThan(root.<Integer>get("stock"), 0));
        }
        return ps.toArray(new Predicate[0]);
    }

    private List<Order> orders(CriteriaBuilder cb, Root<Product> root, ProductFilter f, SortMode sort) {
        List<Order> orders = new ArrayList<>();
        switch (sort) {
            case PRICE_ASC -> orders.add(cb.asc(root.get("price")));
            case PRICE_DESC -> orders.add(cb.desc(root.get("price")));
            case NEWEST -> orders.add(cb.desc(root.get("createdAt")));
            case POPULARITY -> {
                orders.add(cb.desc(root.get("sold")));
                orders.add(cb.desc(root.get("rating")));
            }
            case PERTINENCE -> {
                if (f.hasQuery()) {
                    // Les produits dont le nom commence par la recherche passent en premier
                    String first = f.q().trim().split("\\s+")[0];
                    Expression<Integer> startsWith = cb.<Integer>selectCase()
                            .when(cb.like(root.get("searchText"), escapeLike(first) + "%", ESCAPE), 0)
                            .otherwise(1);
                    orders.add(cb.asc(startsWith));
                }
                orders.add(cb.desc(root.get("sold")));
                orders.add(cb.desc(root.get("createdAt")));
            }
        }
        orders.add(cb.asc(root.get("id")));   // pagination stable
        return orders;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

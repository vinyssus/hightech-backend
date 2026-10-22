package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.domain.catalog.ProductStatus;
import cm.hitechdz.auth.domain.catalog.Review;
import cm.hitechdz.auth.domain.catalog.ReviewHelpfulVote;
import cm.hitechdz.auth.dto.PageResponse;
import cm.hitechdz.auth.dto.catalog.AdminReviewDto;
import cm.hitechdz.auth.dto.catalog.HelpfulResponse;
import cm.hitechdz.auth.dto.catalog.ReviewDto;
import cm.hitechdz.auth.dto.catalog.ReviewPage;
import cm.hitechdz.auth.dto.catalog.ReviewRequest;
import cm.hitechdz.auth.dto.catalog.ReviewSummary;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.UserRepository;
import cm.hitechdz.auth.repository.catalog.ProductRepository;
import cm.hitechdz.auth.repository.catalog.ReviewHelpfulVoteRepository;
import cm.hitechdz.auth.repository.catalog.ReviewRepository;
import cm.hitechdz.auth.repository.order.OrderItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    static final String ALREADY_REVIEWED = "Vous avez déjà donné votre avis sur ce produit.";
    static final String ALREADY_VOTED = "Vous avez déjà indiqué que cet avis était utile.";
    static final String REVIEW_NOT_FOUND = "Avis introuvable.";
    static final String INVALID_SORT = "Tri invalide (recent, rating ou helpful).";

    private final ReviewRepository reviewRepository;
    private final ReviewHelpfulVoteRepository voteRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderItemRepository orderItemRepository;

    public ReviewService(ReviewRepository reviewRepository, ReviewHelpfulVoteRepository voteRepository,
                         ProductRepository productRepository, UserRepository userRepository,
                         OrderItemRepository orderItemRepository) {
        this.reviewRepository = reviewRepository;
        this.voteRepository = voteRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public ReviewPage list(String slug, String sort, int page, int size) {
        Product product = visibleProduct(slug);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), sortOf(sort));
        Page<Review> result = reviewRepository.findByProductId(product.getId(), pageable);
        List<ReviewDto> content = result.getContent().stream().map(ReviewService::toDto).toList();
        return new ReviewPage(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), summary(product.getId()));
    }

    @Transactional
    public ReviewDto create(String slug, UUID userId, ReviewRequest req) {
        Product product = visibleProduct(slug);
        if (reviewRepository.existsByProductIdAndUserId(product.getId(), userId)) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_REVIEWED);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Utilisateur introuvable. Veuillez vous reconnecter."));

        Review r = new Review();
        r.setProduct(product);
        r.setUserId(userId);
        r.setAuthorName(user.getFirstName());
        r.setAuthorCity(req.city() == null || req.city().isBlank() ? null : req.city().trim());
        r.setRating(req.rating());
        r.setTitle(req.title().trim());
        r.setBody(req.body().trim());
        r.setVerified(orderItemRepository.existsDeliveredPurchase(userId, product.getId()));
        reviewRepository.saveAndFlush(r);

        recomputeRating(product);
        return toDto(r);
    }

    /** Un seul vote « utile » par client et par avis. */
    @Transactional
    public HelpfulResponse markHelpful(Long reviewId, UUID userId) {
        if (!reviewRepository.existsById(reviewId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, REVIEW_NOT_FOUND);
        }
        ReviewHelpfulVote.Key key = new ReviewHelpfulVote.Key(reviewId, userId);
        if (voteRepository.existsById(key)) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_VOTED);
        }
        voteRepository.saveAndFlush(new ReviewHelpfulVote(reviewId, userId));
        reviewRepository.incrementHelpful(reviewId);
        return new HelpfulResponse(reviewRepository.findById(reviewId).map(Review::getHelpfulCount).orElse(0));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminReviewDto> adminList(String productId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<Review> result = productId == null || productId.isBlank()
                ? reviewRepository.findAllWithProduct(pageable)
                : reviewRepository.findByProductIdWithProduct(productId, pageable);
        return PageResponse.from(result.map(ReviewService::toAdminDto));
    }

    /** Modération : suppression puis recalcul de la note du produit. */
    @Transactional
    public void delete(Long reviewId) {
        Review r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, REVIEW_NOT_FOUND));
        Product product = r.getProduct();
        reviewRepository.delete(r);
        reviewRepository.flush();
        recomputeRating(product);
    }

    /** rating = moyenne arrondie à 1 décimale, reviews = nombre d'avis. */
    @Transactional
    public void recomputeRating(Product product) {
        ReviewSummary s = summary(product.getId());
        product.setRating(s.average());
        product.setReviewsCount((int) s.count());
        productRepository.flush();
    }

    // ------------------------------------------------------------------ helpers
    ReviewSummary summary(String productId) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            distribution.put(String.valueOf(star), 0L);
        }
        long count = 0;
        long total = 0;
        for (Object[] row : reviewRepository.ratingDistribution(productId)) {
            int rating = ((Number) row[0]).intValue();
            long n = ((Number) row[1]).longValue();
            distribution.put(String.valueOf(rating), n);
            count += n;
            total += rating * n;
        }
        double average = count == 0 ? 0 : BigDecimal.valueOf((double) total / count)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new ReviewSummary(average, count, distribution);
    }

    private Product visibleProduct(String slug) {
        return productRepository.findVisibleBySlug(slug, ProductStatus.PUBLISHED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, CatalogQueryService.PRODUCT_NOT_FOUND));
    }

    private static Sort sortOf(String sort) {
        String value = sort == null || sort.isBlank() ? "recent" : sort.trim();
        Sort recent = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return switch (value) {
            case "recent" -> recent;
            case "rating" -> Sort.by(Sort.Direction.DESC, "rating").and(recent);
            case "helpful" -> Sort.by(Sort.Direction.DESC, "helpfulCount").and(recent);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_SORT);
        };
    }

    private static ReviewDto toDto(Review r) {
        return new ReviewDto(r.getId(), r.getRating(), r.getTitle(), r.getBody(), r.isVerified(),
                r.getHelpfulCount(), r.getCreatedAt(), r.getAuthorName(), r.getAuthorCity());
    }

    private static AdminReviewDto toAdminDto(Review r) {
        Product p = r.getProduct();
        return new AdminReviewDto(r.getId(), p.getId(), p.getSlug(), p.getName(), r.getUserId(),
                r.getAuthorName(), r.getAuthorCity(), r.getRating(), r.getTitle(), r.getBody(),
                r.isVerified(), r.getHelpfulCount(), r.getCreatedAt());
    }
}

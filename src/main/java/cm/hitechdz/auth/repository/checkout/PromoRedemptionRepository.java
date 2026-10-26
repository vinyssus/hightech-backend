package cm.hitechdz.auth.repository.checkout;

import cm.hitechdz.auth.domain.checkout.PromoRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PromoRedemptionRepository extends JpaRepository<PromoRedemption, Long> {

    long countByPromoIdAndCustomerKey(Long promoId, String customerKey);

    Optional<PromoRedemption> findByOrderId(UUID orderId);
}

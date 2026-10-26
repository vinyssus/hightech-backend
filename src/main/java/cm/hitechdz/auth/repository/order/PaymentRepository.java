package cm.hitechdz.auth.repository.order;

import cm.hitechdz.auth.domain.order.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    List<Payment> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    Optional<Payment> findByProviderAndProviderReference(String provider, String providerReference);

    @Query("select p from Payment p where p.order.id = :orderId and p.status = cm.hitechdz.auth.domain.order.Payment.Status.PENDING")
    List<Payment> findPendingByOrder(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") String id);

    @Query("select p.id from Payment p where p.status = cm.hitechdz.auth.domain.order.Payment.Status.PENDING and p.expiresAt < :now")
    List<String> findExpiredPending(@Param("now") Instant now);
}

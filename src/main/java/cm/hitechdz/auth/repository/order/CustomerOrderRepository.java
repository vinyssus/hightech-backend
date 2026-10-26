package cm.hitechdz.auth.repository.order;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID>, JpaSpecificationExecutor<CustomerOrder> {

    Optional<CustomerOrder> findByNumber(String number);

    Optional<CustomerOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByNumber(String number);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.number = :number")
    Optional<CustomerOrder> findByNumberForUpdate(@Param("number") String number);

    Page<CustomerOrder> findByUserId(UUID userId, Pageable pageable);

    /** Commandes à payer en ligne dont la réservation de stock a expiré. */
    @Query("""
            select o.id from CustomerOrder o
            where o.status = :status and o.stockReserved = true and o.expiresAt is not null and o.expiresAt < :now
            """)
    List<UUID> findExpiredReservations(@Param("status") OrderStatus status, @Param("now") Instant now);
}

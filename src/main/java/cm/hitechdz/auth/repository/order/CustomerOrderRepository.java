package cm.hitechdz.auth.repository.order;

import cm.hitechdz.auth.domain.order.CustomerOrder;
import cm.hitechdz.auth.domain.order.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findByIdForUpdate(@Param("id") UUID id);

    @Query("select o.id from CustomerOrder o where o.status = :status and o.expiresAt < :now")
    List<UUID> findExpiredIds(@Param("status") OrderStatus status, @Param("now") Instant now);
}

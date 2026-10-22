package cm.hitechdz.auth.repository.order;

import cm.hitechdz.auth.domain.order.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    boolean existsByProductId(String productId);

    /** Achat vérifié : le client a une commande livrée contenant ce produit. */
    @Query("""
            select count(i) > 0 from OrderItem i
            where i.productId = :productId and i.order.userId = :userId
              and i.order.status = cm.hitechdz.auth.domain.order.OrderStatus.DELIVERED
            """)
    boolean existsDeliveredPurchase(@Param("userId") UUID userId, @Param("productId") String productId);
}

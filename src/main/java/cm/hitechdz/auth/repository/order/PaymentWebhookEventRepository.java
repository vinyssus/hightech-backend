package cm.hitechdz.auth.repository.order;

import cm.hitechdz.auth.domain.order.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {
}

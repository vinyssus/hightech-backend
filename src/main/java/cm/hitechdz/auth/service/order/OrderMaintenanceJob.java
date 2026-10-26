package cm.hitechdz.auth.service.order;

import cm.hitechdz.auth.repository.checkout.CheckoutQuoteRepository;
import cm.hitechdz.auth.service.payment.PaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Tâches idempotentes : expiration des paiements, libération des réservations, purge des devis. */
@Component
public class OrderMaintenanceJob {

    private final PaymentService payments;
    private final CheckoutQuoteRepository quoteRepository;

    public OrderMaintenanceJob(PaymentService payments, CheckoutQuoteRepository quoteRepository) {
        this.payments = payments;
        this.quoteRepository = quoteRepository;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void run() {
        payments.expirePayments();
        payments.releaseExpiredReservations();
    }

    @Scheduled(cron = "0 45 4 * * *", zone = "Africa/Douala")
    @Transactional
    public void purgeQuotes() {
        quoteRepository.deleteExpired(Instant.now().minus(Duration.ofDays(1)));
    }
}

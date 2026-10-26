package cm.hitechdz.auth.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Choix explicite du prestataire : PAYMENT_PROVIDER = none (défaut) | loov | sandbox.
 * Sans prestataire configuré, le paiement en ligne n'est pas proposé et n'est jamais simulé.
 */
@Component
public class PaymentProviders {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviders.class);

    private final List<PaymentProvider> providers;
    private final String selected;

    public PaymentProviders(List<PaymentProvider> providers, @Value("${app.payments.provider:none}") String selected) {
        this.providers = providers;
        this.selected = selected == null ? "none" : selected.trim().toLowerCase(Locale.ROOT);
        Optional<PaymentProvider> active = active();
        if (active.isPresent()) {
            if (SandboxPaymentProvider.NAME.equals(active.get().name())) {
                log.warn("PAIEMENTS EN MODE SANDBOX : aucun débit réel. Ne pas utiliser en production.");
            } else {
                log.info("Paiement en ligne actif via {}", active.get().name());
            }
        } else if (!"none".equals(this.selected)) {
            log.warn("PAYMENT_PROVIDER={} mais ses identifiants sont incomplets : paiement en ligne désactivé", this.selected);
        } else {
            log.info("Aucun prestataire de paiement en ligne : seul le paiement à la livraison est proposé");
        }
    }

    /** Prestataire actif pour les nouveaux paiements. */
    public Optional<PaymentProvider> active() {
        return providers.stream().filter(p -> p.name().equals(selected) && p.configured()).findFirst();
    }

    /** Prestataire par nom (webhooks, paiements existants), même s'il n'est plus sélectionné. */
    public Optional<PaymentProvider> byName(String name) {
        return providers.stream().filter(p -> p.name().equalsIgnoreCase(name) && p.configured()).findFirst();
    }
}

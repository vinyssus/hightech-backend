package cm.hitechdz.auth.service.payment;

/** Prestataire capable de terminer un paiement de test (mode test, aucun argent réel). */
public interface TestSimulation {

    /** outcome : succeeded ou failed. */
    void simulate(String providerReference, String outcome);
}

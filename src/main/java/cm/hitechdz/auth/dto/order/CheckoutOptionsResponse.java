package cm.hitechdz.auth.dto.order;

import java.util.List;

/** paymentMethods ne contient ORANGE_MONEY / MTN_MOMO que si un prestataire de paiement est configuré. */
public record CheckoutOptionsResponse(String currency, Long freeShippingFrom, List<ZoneOption> shippingZones,
                                      List<String> paymentMethods) {}

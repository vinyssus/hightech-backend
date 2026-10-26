package cm.hitechdz.auth.dto.order;

public record PaymentStatusResponse(String paymentId, String orderNumber, String status, long amount,
                                    String currency, String providerReference, String message) {}

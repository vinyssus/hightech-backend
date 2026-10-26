package cm.hitechdz.auth.dto.order;

import java.time.Instant;

public record PaymentInitResponse(String paymentId, String orderNumber, String operator, String status,
                                  long amount, String currency, Instant expiresAt, String message,
                                  String checkoutUrl) {}

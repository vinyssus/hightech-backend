package cm.hitechdz.auth.domain.order;

public enum PaymentMethod {
    CASH_ON_DELIVERY,
    ORANGE_MONEY,
    MTN_MOMO;

    public boolean online() {
        return this != CASH_ON_DELIVERY;
    }
}

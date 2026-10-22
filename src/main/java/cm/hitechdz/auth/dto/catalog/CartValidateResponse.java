package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/** subtotal = somme des lignes disponibles ; les codes promo et la livraison s'y brancheront. */
public record CartValidateResponse(List<CartLine> items, long subtotal) {}

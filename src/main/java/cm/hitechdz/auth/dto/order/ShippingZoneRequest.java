package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * id facultatif, utilisé seulement à la création (sinon dérivé du libellé) ; ensuite immuable.
 * Création : label, fee et delay obligatoires ; requiresCity, pickup et codAllowed valent false
 * et active vaut true s'ils sont absents ; position = dernière + 1.
 * Modification (PUT ou PATCH) : les champs absents gardent leur valeur ; la zone obtenue est
 * entièrement revalidée.
 */
public record ShippingZoneRequest(
        @Size(max = 40) String id,
        String label,

        @PositiveOrZero(message = "Les frais doivent être positifs ou nuls.")
        @Max(value = 100_000, message = "Les frais ne peuvent pas dépasser 100 000 FCFA.")
        Long fee,

        String delay,
        Boolean requiresCity,
        Boolean pickup,
        Boolean codAllowed,
        Boolean active,
        Integer position
) {}

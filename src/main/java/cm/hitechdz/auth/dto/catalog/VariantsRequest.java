package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Remplace toutes les combinaisons du produit (liste vide = produit sans variantes). */
public record VariantsRequest(
        @NotNull(message = "La liste des combinaisons est obligatoire.")
        @Size(max = 100, message = "100 combinaisons maximum.")
        List<@Valid VariantInput> combinations
) {}

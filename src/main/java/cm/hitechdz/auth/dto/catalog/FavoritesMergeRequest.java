package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FavoritesMergeRequest(
        @NotNull(message = "La liste des produits est obligatoire.")
        @Size(max = 200, message = "200 favoris maximum.")
        List<String> productIds
) {}

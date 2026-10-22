package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CartValidateRequest(
        @NotNull(message = "Le panier est obligatoire.")
        @Size(max = 50, message = "50 articles maximum.")
        List<@Valid CartItemInput> items
) {}

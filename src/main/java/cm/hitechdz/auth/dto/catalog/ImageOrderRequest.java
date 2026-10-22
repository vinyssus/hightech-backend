package cm.hitechdz.auth.dto.catalog;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ImageOrderRequest(
        @NotEmpty(message = "La liste des images est obligatoire.")
        List<String> publicIds
) {}

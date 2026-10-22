package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Création / modification d'un produit. Le slug n'est jamais lu ici : il est généré par le serveur.
 * Les règles croisées (catégorie existante, oldPrice > price, images https) sont vérifiées par le service.
 */
public record ProductRequest(
        @NotBlank(message = "Le nom est obligatoire.")
        @Size(min = 3, max = 200, message = "Le nom doit contenir entre 3 et 200 caractères.")
        String name,

        @Size(max = 5000, message = "La description ne peut pas dépasser 5000 caractères.")
        String description,

        @NotBlank(message = "La catégorie est obligatoire.")
        String category,

        @NotBlank(message = "La marque est obligatoire.")
        @Size(max = 60, message = "La marque ne peut pas dépasser 60 caractères.")
        String brand,

        @NotNull(message = "Le prix est obligatoire.")
        @Positive(message = "Le prix doit être supérieur à 0.")
        Long price,

        @Positive(message = "L'ancien prix doit être supérieur à 0.")
        Long oldPrice,

        @NotNull(message = "Le stock est obligatoire.")
        @PositiveOrZero(message = "Le stock doit être un entier positif ou nul.")
        Integer stock,

        @Size(max = 8, message = "8 images maximum par produit.")
        List<ImageInput> images,

        @Size(max = 50, message = "50 caractéristiques maximum.")
        List<@Valid SpecDto> specs,

        String status,

        @JsonProperty("isNew")
        Boolean isNew
) {}

package cm.hitechdz.auth.dto.order;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Coordonnées de livraison. Le téléphone est normalisé au format +2376XXXXXXXX par le serveur. */
public record DeliveryInput(
        @NotBlank(message = "Le nom est obligatoire.")
        @Size(min = 2, max = 80, message = "Le nom doit contenir entre 2 et 80 caractères.")
        String name,

        @NotBlank(message = "Le téléphone est obligatoire.")
        String phone,

        @Email(message = "Adresse e-mail invalide.")
        @Size(max = 254, message = "L'adresse e-mail est trop longue.")
        String email,

        @NotBlank(message = "Choisissez une zone de livraison.")
        String zoneId,

        @Size(max = 80, message = "La ville ne peut pas dépasser 80 caractères.")
        String city,

        @Size(max = 255, message = "L'adresse ne peut pas dépasser 255 caractères.")
        String address,

        @Size(max = 500, message = "Les instructions ne peuvent pas dépasser 500 caractères.")
        String notes
) {}

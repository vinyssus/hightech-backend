package cm.hitechdz.auth.dto.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Partie « data » (JSON) des envois multipart. slot : MAIN, SIDE_TOP ou SIDE_BOTTOM ;
 * linkType : CATEGORY, SUBCATEGORY, PRODUCT, URL ou NONE ; linkValue : slug catégorie, « categorie/sous-categorie »,
 * slug produit ou URL https. position et active facultatifs. removeMobileImage (modification) retire la version mobile.
 */
public record BannerData(
        @NotBlank(message = "L'emplacement est obligatoire (MAIN, SIDE_TOP ou SIDE_BOTTOM).")
        String slot,
        @NotBlank(message = "Le texte alternatif est obligatoire.")
        @Size(min = 3, max = 150, message = "Le texte alternatif doit contenir entre 3 et 150 caractères.")
        String altText,
        String linkType,
        @Size(max = 500, message = "Le lien ne peut pas dépasser 500 caractères.")
        String linkValue,
        @PositiveOrZero(message = "La position doit être positive ou nulle.")
        Integer position,
        Boolean active,
        Instant startsAt,
        Instant endsAt,
        Boolean removeMobileImage
) {}

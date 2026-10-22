package cm.hitechdz.auth.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Option d'un groupe de variantes. hex uniquement pour les couleurs ; delta en FCFA. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariantOption(String label, String hex, long delta, boolean available) {}

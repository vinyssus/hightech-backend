package cm.hitechdz.auth.dto.catalog;

import java.util.List;

/** Groupe affiché sur la fiche : « Couleur », « Capacité » ou « RAM ». */
public record VariantGroup(String name, List<VariantOption> options) {}

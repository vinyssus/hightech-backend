package cm.hitechdz.auth.service.catalog;

import cm.hitechdz.auth.domain.catalog.Product;
import cm.hitechdz.auth.util.SlugUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Texte de recherche d'un produit : nom, marque, catégorie et sous-catégories, sans accents ni casse.
 * Le nom est placé en premier (utilisé par le tri « pertinence »).
 */
final class SearchText {

    private static final int MAX_LENGTH = 1000;

    private SearchText() {}

    static void refresh(Product p) {
        List<String> parts = new ArrayList<>();
        parts.add(p.getName());
        if (p.getBrand() != null) {
            parts.add(p.getBrand().getName());
        }
        if (p.getCategory() != null) {
            parts.add(p.getCategory().getName());
            parts.addAll(p.getCategory().getSub());
        }
        String text = SlugUtil.normalize(String.join(" ", parts));
        p.setSearchText(text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text);
    }
}

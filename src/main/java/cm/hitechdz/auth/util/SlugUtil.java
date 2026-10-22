package cm.hitechdz.auth.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Slugs et normalisation de texte, partagés par les catégories, marques et produits.
 * « Écouteurs Sans-Fil & Audio » → ecouteurs-sans-fil-audio
 */
public final class SlugUtil {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final int MAX_LENGTH = 200;

    private SlugUtil() {}

    /** Minuscules, accents retirés (NFD), tout le reste devient « - », tirets fusionnés et retirés aux bords. */
    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String s = Normalizer.normalize(input, Normalizer.Form.NFD);
        s = DIACRITICS.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = NON_ALNUM.matcher(s).replaceAll("-");
        s = EDGE_DASHES.matcher(s).replaceAll("");
        if (s.length() > MAX_LENGTH) {
            s = EDGE_DASHES.matcher(s.substring(0, MAX_LENGTH)).replaceAll("");
        }
        return s;
    }

    /** Texte de recherche : même normalisation que les slugs, avec des espaces entre les mots. */
    public static String normalize(String input) {
        return slugify(input).replace('-', ' ');
    }
}

package cm.hitechdz.auth.util;

import java.util.regex.Pattern;

/**
 * Texte brut à partir d'une saisie libre : retire les balises HTML, les blocs script/style
 * et les caractères de contrôle (sauf retours à la ligne). L'échappement à l'affichage reste
 * à la charge du frontend.
 */
public final class PlainText {

    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|iframe|object|embed)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)</?[a-zA-Z!/][^>]*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private PlainText() {}

    public static String clean(String input) {
        if (input == null) {
            return null;
        }
        String s = input.replace("\r\n", "\n").replace('\r', '\n');
        s = SCRIPT_STYLE.matcher(s).replaceAll(" ");
        s = COMMENT.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("<", "").replace(">", "");      // chevrons orphelins
        s = CONTROL.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ");
        s = BLANK_LINES.matcher(s).replaceAll("\n\n");
        return s.lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("").strip();
    }

    /** Version sur une seule ligne (titre, ville). */
    public static String cleanLine(String input) {
        String s = clean(input);
        return s == null ? null : s.replace('\n', ' ').replaceAll(" {2,}", " ").strip();
    }
}

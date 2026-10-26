package cm.hitechdz.auth.util;

import java.util.regex.Pattern;

/** Numéros camerounais : normalisation au format international +2376XXXXXXXX. */
public final class PhoneUtil {

    private static final Pattern MOBILE = Pattern.compile("^6\\d{8}$");

    private PhoneUtil() {}

    /** @return +2376XXXXXXXX, ou null si le numéro n'est pas un mobile camerounais valide */
    public static String normalizeCameroon(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("00237")) {
            digits = digits.substring(5);
        } else if (digits.startsWith("237") && digits.length() == 12) {
            digits = digits.substring(3);
        }
        return MOBILE.matcher(digits).matches() ? "+237" + digits : null;
    }
}

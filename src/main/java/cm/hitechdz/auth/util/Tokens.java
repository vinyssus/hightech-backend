package cm.hitechdz.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Jetons aléatoires, références opaques et empreintes SHA-256. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Sans caractères ambigus (0/O, 1/I/L) pour les références lues au téléphone. */
    private static final char[] REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private Tokens() {}

    /** Jeton aléatoire en Base64 URL (bytes octets d'entropie). */
    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String reference(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparaison à temps constant. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

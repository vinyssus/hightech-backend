package cm.hitechdz.auth.dto;

/**
 * Règles de validation partagées (alignées sur le frontend).
 */
public final class ValidationRules {

    /** Format camerounais, ex. "6 77 12 34 56", "+237 677123456". */
    public static final String PHONE_REGEX = "^(\\+?237)?\\s?6(\\s?\\d){8}$";

    /** 8 caractères minimum, au moins une majuscule et un chiffre. */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Z])(?=.*\\d).{8,}$";

    public static final String PASSWORD_MESSAGE =
            "Le mot de passe doit contenir au moins 8 caractères, dont une majuscule et un chiffre.";

    public static final String PHONE_MESSAGE =
            "Numéro de téléphone camerounais invalide (ex. 6 77 12 34 56).";

    /** BCrypt ignore les octets au-delà de 72. */
    public static final int PASSWORD_MAX = 72;

    private ValidationRules() {}
}

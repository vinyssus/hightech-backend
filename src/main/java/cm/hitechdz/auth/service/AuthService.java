package cm.hitechdz.auth.service;

import cm.hitechdz.auth.config.AppProperties;
import cm.hitechdz.auth.domain.PasswordResetCode;
import cm.hitechdz.auth.domain.Role;
import cm.hitechdz.auth.domain.User;
import cm.hitechdz.auth.dto.AuthResponse;
import cm.hitechdz.auth.dto.ChangePasswordRequest;
import cm.hitechdz.auth.dto.LoginRequest;
import cm.hitechdz.auth.dto.RegisterRequest;
import cm.hitechdz.auth.dto.ResetPasswordRequest;
import cm.hitechdz.auth.dto.UpdateProfileRequest;
import cm.hitechdz.auth.dto.UserDto;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.PasswordResetCodeRepository;
import cm.hitechdz.auth.repository.UserRepository;
import cm.hitechdz.auth.security.JwtService;
import cm.hitechdz.auth.service.mail.MailService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    static final String EMAIL_TAKEN_REGISTER = "Un compte existe déjà avec cette adresse e-mail.";
    static final String EMAIL_TAKEN_PROFILE = "Cette adresse e-mail est déjà utilisée par un autre compte.";
    static final String BAD_CREDENTIALS = "E-mail ou mot de passe incorrect.";
    static final String INVALID_CODE = "Code invalide ou expiré. Demandez un nouveau code.";
    static final String WRONG_CURRENT_PASSWORD = "Mot de passe actuel incorrect.";
    static final String SAME_PASSWORD = "Le nouveau mot de passe doit être différent de l'actuel.";
    static final String USER_NOT_FOUND = "Utilisateur introuvable. Veuillez vous reconnecter.";

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository resetCodeRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final AppProperties.PasswordReset resetProps;
    private final SecureRandom random = new SecureRandom();

    /** Hash factice : égalise le temps de réponse quand l'e-mail n'existe pas (anti-énumération). */
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       PasswordResetCodeRepository resetCodeRepository,
                       RefreshTokenService refreshTokenService,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       MailService mailService,
                       AppProperties props) {
        this.userRepository = userRepository;
        this.resetCodeRepository = resetCodeRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.resetProps = props.passwordReset();
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    // ------------------------------------------------------------------ 1. register
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, EMAIL_TAKEN_REGISTER, Map.of("email", EMAIL_TAKEN_REGISTER));
        }
        User user = new User();
        user.setFirstName(req.firstName().trim());
        user.setLastName(req.lastName().trim());
        user.setEmail(email);
        user.setPhone(req.phone().trim());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(Role.CUSTOMER);
        userRepository.saveAndFlush(user);

        // Connexion automatique : session "courte" (1 jour) par défaut
        return buildAuthResponse(user, false);
    }

    // ------------------------------------------------------------------ 2. login
    @Transactional
    public AuthResponse login(LoginRequest req) {
        String email = normalizeEmail(req.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.password(), dummyHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        return buildAuthResponse(user, req.remember());
    }

    // ------------------------------------------------------------------ 3. refresh
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
        User user = rotation.user();
        return new AuthResponse(UserDto.from(user), jwtService.generateAccessToken(user), rotation.refreshToken());
    }

    // ------------------------------------------------------------------ 4. logout
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    // ------------------------------------------------------------------ 5. me
    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        return UserDto.from(loadUser(userId));
    }

    // ------------------------------------------------------------------ 6. forgot-password
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        userRepository.findByEmail(email).ifPresentOrElse(user -> {
            resetCodeRepository.deleteByUserId(user.getId());

            String code = String.format("%06d", random.nextInt(1_000_000));
            PasswordResetCode reset = new PasswordResetCode();
            reset.setUser(user);
            reset.setCodeHash(passwordEncoder.encode(code));
            reset.setExpiresAt(Instant.now().plus(resetProps.codeTtl()));
            resetCodeRepository.save(reset);

            mailService.sendPasswordResetCode(user.getEmail(), user.getFirstName(), code,
                    resetProps.codeTtl().toMinutes());
        }, () -> passwordEncoder.encode("timing-equalizer")); // coût comparable au cas "compte existant"
    }

    // ------------------------------------------------------------------ 7. reset-password
    /** noRollbackFor : le compteur de tentatives doit être persisté même en cas d'échec. */
    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(ResetPasswordRequest req) {
        String email = normalizeEmail(req.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.code(), dummyHash);
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        PasswordResetCode reset = resetCodeRepository.findByUserId(user.getId()).orElse(null);
        if (reset == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        if (reset.isExpired(Instant.now())) {
            resetCodeRepository.delete(reset);
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        if (!passwordEncoder.matches(req.code(), reset.getCodeHash())) {
            reset.incrementAttempts();
            if (reset.getAttempts() >= resetProps.maxAttempts()) {
                resetCodeRepository.delete(reset);   // trop d'essais : le code est brûlé
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        user.setPasswordHash(passwordEncoder.encode(req.password()));
        resetCodeRepository.delete(reset);
        refreshTokenService.revokeAll(user.getId());
    }

    // ------------------------------------------------------------------ 8. profile
    @Transactional
    public UserDto updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = loadUser(userId);
        String email = normalizeEmail(req.email());
        if (!email.equals(user.getEmail()) && userRepository.existsByEmailAndIdNot(email, userId)) {
            throw new ApiException(HttpStatus.CONFLICT, EMAIL_TAKEN_PROFILE, Map.of("email", EMAIL_TAKEN_PROFILE));
        }
        user.setFirstName(req.firstName().trim());
        user.setLastName(req.lastName().trim());
        user.setEmail(email);
        user.setPhone(req.phone().trim());
        userRepository.saveAndFlush(user);
        return UserDto.from(user);
    }

    // ------------------------------------------------------------------ 9. password
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = loadUser(userId);
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, WRONG_CURRENT_PASSWORD,
                    Map.of("currentPassword", WRONG_CURRENT_PASSWORD));
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, SAME_PASSWORD, Map.of("newPassword", SAME_PASSWORD));
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
    }

    // ------------------------------------------------------------------ helpers
    private AuthResponse buildAuthResponse(User user, boolean remember) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user, remember);
        return new AuthResponse(UserDto.from(user), accessToken, refreshToken);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, USER_NOT_FOUND));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

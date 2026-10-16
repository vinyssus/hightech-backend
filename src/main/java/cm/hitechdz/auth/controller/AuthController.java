package cm.hitechdz.auth.controller;

import cm.hitechdz.auth.dto.AuthResponse;
import cm.hitechdz.auth.dto.ChangePasswordRequest;
import cm.hitechdz.auth.dto.ForgotPasswordRequest;
import cm.hitechdz.auth.dto.LoginRequest;
import cm.hitechdz.auth.dto.MessageResponse;
import cm.hitechdz.auth.dto.RefreshTokenRequest;
import cm.hitechdz.auth.dto.RegisterRequest;
import cm.hitechdz.auth.dto.ResetPasswordRequest;
import cm.hitechdz.auth.dto.UpdateProfileRequest;
import cm.hitechdz.auth.dto.UserDto;
import cm.hitechdz.auth.security.AuthenticatedUser;
import cm.hitechdz.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public MessageResponse logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return new MessageResponse("Déconnexion réussie.");
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.me(principal.id());
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return new MessageResponse(
                "Si un compte existe avec cette adresse, un code de réinitialisation vient d'être envoyé.");
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new MessageResponse("Mot de passe réinitialisé avec succès. Vous pouvez vous connecter.");
    }

    @PutMapping("/profile")
    public UserDto updateProfile(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return authService.updateProfile(principal.id(), request);
    }

    @PutMapping("/password")
    public MessageResponse changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.id(), request);
        return new MessageResponse("Mot de passe modifié avec succès.");
    }
}

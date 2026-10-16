package cm.hitechdz.auth.dto;

public record AuthResponse(UserDto user, String accessToken, String refreshToken) {}

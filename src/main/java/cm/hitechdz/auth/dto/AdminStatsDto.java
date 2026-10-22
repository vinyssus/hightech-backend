package cm.hitechdz.auth.dto;

public record AdminStatsDto(
        long totalUsers,
        long customers,
        long admins,
        long blockedUsers,
        long newUsersLast7Days,
        long newUsersLast30Days
) {}

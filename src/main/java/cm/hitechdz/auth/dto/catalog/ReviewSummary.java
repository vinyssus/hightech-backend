package cm.hitechdz.auth.dto.catalog;

import java.util.Map;

/** distribution : { "5": n, "4": n, "3": n, "2": n, "1": n }. */
public record ReviewSummary(double average, long count, Map<String, Long> distribution) {}

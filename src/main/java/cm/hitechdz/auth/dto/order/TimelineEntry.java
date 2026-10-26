package cm.hitechdz.auth.dto.order;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record TimelineEntry(String status, String label, Instant at,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String note) {}

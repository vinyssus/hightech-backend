package cm.hitechdz.auth.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Format de pagination stable exposé au frontend (page commence à 0). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}

package cm.hitechdz.auth.dto.catalog;

import java.util.List;

public record CategoryDto(String slug, String name, String icon, List<String> sub) {}

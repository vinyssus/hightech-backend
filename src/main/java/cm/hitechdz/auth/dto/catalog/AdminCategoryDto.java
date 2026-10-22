package cm.hitechdz.auth.dto.catalog;

import java.util.List;

public record AdminCategoryDto(String slug, String name, String icon, List<String> sub, long productCount) {}

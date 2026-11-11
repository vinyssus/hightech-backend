package cm.hitechdz.auth.dto.assistant;

import java.util.List;

public record AssistantStatus(boolean enabled, String welcome, List<String> suggestions) {}

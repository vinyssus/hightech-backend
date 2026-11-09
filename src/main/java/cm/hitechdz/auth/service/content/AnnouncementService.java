package cm.hitechdz.auth.service.content;

import cm.hitechdz.auth.domain.content.Announcement;
import cm.hitechdz.auth.dto.content.AnnouncementDto;
import cm.hitechdz.auth.dto.content.AnnouncementRequest;
import cm.hitechdz.auth.dto.content.PublicAnnouncementDto;
import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.repository.content.AnnouncementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AnnouncementService {

    static final String NOT_FOUND = "Annonce introuvable.";
    static final String INVALID_FIELDS = "Veuillez corriger les champs indiqués.";
    static final String BAD_LINK = "Le lien doit être une URL http(s) ou un chemin commençant par /";
    static final String BAD_DATES = "La date de fin doit être postérieure à la date de début";

    private final AnnouncementRepository repository;

    public AnnouncementService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PublicAnnouncementDto> visible() {
        return repository.findVisible(Instant.now()).stream()
                .map(a -> new PublicAnnouncementDto(a.getId(), a.getMessage(), a.getLink()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnnouncementDto> adminList() {
        return repository.findAllOrdered().stream().map(AnnouncementService::toDto).toList();
    }

    @Transactional
    public AnnouncementDto create(AnnouncementRequest req) {
        Announcement a = new Announcement();
        apply(a, req);
        a.setActive(req.active() == null || req.active());
        a.setPosition(req.position() != null ? req.position() : repository.maxPosition() + 1);
        return toDto(repository.saveAndFlush(a));
    }

    @Transactional
    public AnnouncementDto update(Long id, AnnouncementRequest req) {
        Announcement a = load(id);
        apply(a, req);
        if (req.active() != null) {
            a.setActive(req.active());
        }
        if (req.position() != null) {
            a.setPosition(req.position());
        }
        return toDto(repository.saveAndFlush(a));
    }

    @Transactional
    public AnnouncementDto setActive(Long id, boolean active) {
        Announcement a = load(id);
        a.setActive(active);
        return toDto(repository.saveAndFlush(a));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(load(id));
    }

    private static void apply(Announcement a, AnnouncementRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        String link = normalizeLink(req.link());
        if (link != null && !validLink(link)) {
            errors.put("link", BAD_LINK);
        }
        if (req.startsAt() != null && req.endsAt() != null && req.endsAt().isBefore(req.startsAt())) {
            errors.put("endsAt", BAD_DATES);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_FIELDS, errors);
        }
        a.setMessage(req.message().trim());
        a.setLink(link);
        a.setStartsAt(req.startsAt());
        a.setEndsAt(req.endsAt());
    }

    private static String normalizeLink(String link) {
        return link == null || link.isBlank() ? null : link.trim();
    }

    /** Chemin interne (/catalogue, mais pas //domaine) ou URL absolue http/https avec un hôte. */
    static boolean validLink(String link) {
        if (link.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            return false;
        }
        if (link.startsWith("/")) {
            return !link.startsWith("//") && !link.startsWith("/\\");
        }
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private Announcement load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static AnnouncementDto toDto(Announcement a) {
        return new AnnouncementDto(a.getId(), a.getMessage(), a.getLink(), a.isActive(), a.getPosition(),
                a.getStartsAt(), a.getEndsAt(), a.getCreatedAt(), a.getUpdatedAt());
    }
}

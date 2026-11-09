package cm.hitechdz.auth.controller.content;

import cm.hitechdz.auth.dto.content.AnnouncementActiveRequest;
import cm.hitechdz.auth.dto.content.AnnouncementDto;
import cm.hitechdz.auth.dto.content.AnnouncementRequest;
import cm.hitechdz.auth.service.content.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestion du bandeau d'annonces (ADMIN). */
@RestController
@RequestMapping("/api/admin/announcements")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnnouncementController {

    private final AnnouncementService service;

    public AdminAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnnouncementDto> list() {
        return service.adminList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementDto create(@Valid @RequestBody AnnouncementRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AnnouncementDto update(@PathVariable Long id, @Valid @RequestBody AnnouncementRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/active")
    public AnnouncementDto setActive(@PathVariable Long id, @Valid @RequestBody AnnouncementActiveRequest request) {
        return service.setActive(id, request.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}

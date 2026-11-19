package cm.hitechdz.auth.controller.dashboard;

import cm.hitechdz.auth.dto.dashboard.DashboardResponse;
import cm.hitechdz.auth.service.dashboard.DashboardService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Page d'accueil de l'administration : indicateurs calculés à partir des commandes réelles. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final DashboardService dashboard;

    public AdminDashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/admin/dashboard")
    public ResponseEntity<DashboardResponse> get(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dashboard.dashboard(days));
    }
}

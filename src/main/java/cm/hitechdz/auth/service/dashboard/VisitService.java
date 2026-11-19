package cm.hitechdz.auth.service.dashboard;

import cm.hitechdz.auth.exception.ApiException;
import cm.hitechdz.auth.util.Tokens;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Visites du site pour le taux de conversion. Le navigateur envoie un identifiant aléatoire
 * (aucune donnée personnelle) ; seul SHA-256(jour|identifiant) est conservé, une fois par jour.
 */
@Service
public class VisitService {

    private static final Pattern VISITOR_ID = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public VisitService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    VisitService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** true si c'est la première visite du jour pour ce navigateur. */
    public boolean record(String visitorId) {
        if (visitorId == null || !VISITOR_ID.matcher(visitorId.trim()).matches()) {
            String msg = "Identifiant de visite invalide.";
            throw new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("visitorId", msg));
        }
        LocalDate day = LocalDate.ofInstant(clock.instant(), DashboardService.ZONE);
        MapSqlParameterSource p = new MapSqlParameterSource("day", Date.valueOf(day))
                .addValue("hash", Tokens.sha256(day + "|" + visitorId.trim()));
        try {
            // Hors transaction : un doublon (déjà compté aujourd'hui) n'affecte aucune autre requête
            return jdbc.update("insert into site_visits (day, visitor_hash) values (:day, :hash)", p) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}

package cm.hitechdz.auth.repository.content;

import cm.hitechdz.auth.domain.content.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Annonces actives dont la période [startsAt, endsAt] contient l'instant donné (bornes nulles = illimitées). */
    @Query("""
            select a from Announcement a
            where a.active = true
              and (a.startsAt is null or a.startsAt <= :now)
              and (a.endsAt is null or a.endsAt >= :now)
            order by a.position asc, a.id asc
            """)
    List<Announcement> findVisible(@Param("now") Instant now);

    @Query("select a from Announcement a order by a.position asc, a.id asc")
    List<Announcement> findAllOrdered();

    @Query("select coalesce(max(a.position), -1) from Announcement a")
    int maxPosition();
}

package cm.hitechdz.auth.repository.content;

import cm.hitechdz.auth.domain.content.BannerSlot;
import cm.hitechdz.auth.domain.content.HomeBanner;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface HomeBannerRepository extends JpaRepository<HomeBanner, Long> {

    /** Visibles maintenant : actives et dans leur période (bornes nulles = illimitées). */
    @Query("""
            select b from HomeBanner b
            where b.active = true
              and (b.startsAt is null or b.startsAt <= :now)
              and (b.endsAt is null or b.endsAt >= :now)
            order by b.slot asc, b.position asc, b.id asc
            """)
    List<HomeBanner> findVisible(@Param("now") Instant now);

    @Query("select b from HomeBanner b order by b.slot asc, b.position asc, b.id asc")
    List<HomeBanner> findAllOrdered();

    /** Verrouille les affiches d'un emplacement (limite par emplacement et réordonnancement sans course). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from HomeBanner b where b.slot = :slot order by b.position asc, b.id asc")
    List<HomeBanner> lockSlot(@Param("slot") BannerSlot slot);
}

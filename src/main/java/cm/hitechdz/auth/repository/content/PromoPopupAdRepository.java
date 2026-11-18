package cm.hitechdz.auth.repository.content;

import cm.hitechdz.auth.domain.content.PromoPopupAd;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PromoPopupAdRepository extends JpaRepository<PromoPopupAd, UUID> {

    @Query("""
            select a from PromoPopupAd a
            where a.active = true
              and (a.startsAt is null or a.startsAt <= :now)
              and (a.endsAt is null or a.endsAt >= :now)
            order by a.position asc, a.createdAt asc
            """)
    List<PromoPopupAd> findVisible(@Param("now") Instant now);

    @Query("select a from PromoPopupAd a order by a.position asc, a.createdAt asc")
    List<PromoPopupAd> findAllOrdered();

    /** Verrouille toutes les publicités (limite d'actives et réordonnancement sans course). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PromoPopupAd a order by a.position asc, a.createdAt asc")
    List<PromoPopupAd> lockAll();
}

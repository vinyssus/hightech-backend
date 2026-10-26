package cm.hitechdz.auth.repository.checkout;

import cm.hitechdz.auth.domain.checkout.PromoCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromoCodeRepository extends JpaRepository<PromoCode, Long> {

    Optional<PromoCode> findByCode(String code);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PromoCode p where p.code = :code")
    Optional<PromoCode> findByCodeForUpdate(@Param("code") String code);

    @Query("select p from PromoCode p order by p.createdAt desc, p.id desc")
    List<PromoCode> findAllOrdered();
}

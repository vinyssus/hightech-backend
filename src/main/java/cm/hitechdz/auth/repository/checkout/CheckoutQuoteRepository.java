package cm.hitechdz.auth.repository.checkout;

import cm.hitechdz.auth.domain.checkout.CheckoutQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface CheckoutQuoteRepository extends JpaRepository<CheckoutQuote, String> {

    @Modifying
    @Query("delete from CheckoutQuote q where q.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}

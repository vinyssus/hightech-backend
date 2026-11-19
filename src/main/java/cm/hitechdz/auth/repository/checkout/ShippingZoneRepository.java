package cm.hitechdz.auth.repository.checkout;

import cm.hitechdz.auth.domain.checkout.ShippingZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShippingZoneRepository extends JpaRepository<ShippingZone, String> {

    @Query("select z from ShippingZone z where z.active = true order by z.position asc, z.id asc")
    List<ShippingZone> findActive();

    @Query("select z from ShippingZone z order by z.position asc, z.id asc")
    List<ShippingZone> findAllOrdered();

    @Query("select z from ShippingZone z where lower(z.label) = lower(:label)")
    List<ShippingZone> findByLabelIgnoreCase(@org.springframework.data.repository.query.Param("label") String label);

    @Query("select coalesce(max(z.position), 0) from ShippingZone z")
    int maxPosition();
}

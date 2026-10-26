package cm.hitechdz.auth.repository.checkout;

import cm.hitechdz.auth.domain.checkout.ShopSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopSettingsRepository extends JpaRepository<ShopSettings, Integer> {
}

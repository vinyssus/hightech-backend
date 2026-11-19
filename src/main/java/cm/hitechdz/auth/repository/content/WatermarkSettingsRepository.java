package cm.hitechdz.auth.repository.content;

import cm.hitechdz.auth.domain.content.WatermarkSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WatermarkSettingsRepository extends JpaRepository<WatermarkSettings, Integer> {

    /** Verrou : un seul envoi du logo par défaut même si plusieurs photos arrivent en même temps. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WatermarkSettings w where w.id = 1")
    Optional<WatermarkSettings> lockSingleton();
}

package yagen.waitmydawn.maa.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface ModPreferenceRepository extends JpaRepository<ModPreference, Long> {
    List<ModPreference> findByUserIdOrderByBuildCountDesc(Long userId);
    List<ModPreference> findByUserIdAndCategory(Long userId, String category);
    List<ModPreference> findByUserIdAndSource(Long userId, String source);
    Optional<ModPreference> findByUserIdAndSlug(Long userId, String slug);

    @Transactional
    void deleteByUserId(Long userId);
}

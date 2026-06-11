package yagen.waitmydawn.maa.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface ModBlacklistRepository extends JpaRepository<ModBlacklist, Long> {
    List<ModBlacklist> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<ModBlacklist> findByUserIdAndSlug(Long userId, String slug);

    @Transactional
    void deleteByUserId(Long userId);
}

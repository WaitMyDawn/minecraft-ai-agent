package yagen.waitmydawn.maa.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface CategoryPreferenceRepository extends JpaRepository<CategoryPreference, Long> {
    List<CategoryPreference> findByUserIdOrderByRankAsc(Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}

package yagen.waitmydawn.maa.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByAccountNumber(String accountNumber);
    Optional<User> findByUsername(String username);
    boolean existsByAccountNumber(String accountNumber);
}

package yagen.waitmydawn.maa.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByAccountNumber(String accountNumber);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByAccountNumber(String accountNumber);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /**
     * 现有账号号的最大值（accountNumber 是字符串，但内容一定是数字）。
     *
     * <p>为什么要它：老的发号逻辑是 {@code 1000 + count()}，有两个坑 ——
     * 并发时两个请求读到同一个 count 就发出同一个号；删过账号之后 count 变小，会发出<b>已经存在</b>的号。
     * 改成"从历史最大值往后接"两个坑一起消掉。用原生 SQL 是因为要 CAST 成数字再取 max。
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT MAX(CAST(accountNumber AS BIGINT)) FROM maa_user",
            nativeQuery = true)
    Long findMaxAccountNumber();
}

package yagen.waitmydawn.maa.controller;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 数据库检测到旧快照写入时，要求客户端重新读取，避免静默覆盖账户安全设置。 */
@RestControllerAdvice
public class ConcurrencyExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(org.springframework.web.server.ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error",
                exception.getReason() == null ? "请求未能完成" : exception.getReason()));
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(409).body(Map.of("error", "账户数据已被其他请求更新，请刷新后重试"));
    }
}

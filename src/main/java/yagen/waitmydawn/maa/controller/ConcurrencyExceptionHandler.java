package yagen.waitmydawn.maa.controller;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 统一请求解析、状态码及乐观锁失败响应，避免回显原始输入或静默覆盖旧快照。 */
@RestControllerAdvice
public class ConcurrencyExceptionHandler {
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidJson(org.springframework.http.converter.HttpMessageNotReadableException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof yagen.waitmydawn.maa.runtime.JsonBodyLimitFilter.LimitExceededException)
                return ResponseEntity.status(413).body(Map.of("error", "请求内容超过字节限额"));
            current = current.getCause();
        }
        return ResponseEntity.badRequest().body(Map.of("error", "请求 JSON 格式无效"));
    }
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

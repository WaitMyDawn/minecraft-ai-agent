package yagen.waitmydawn.maa.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yagen.waitmydawn.maa.service.BackupService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维端点。目前只有一个：在线备份数据库。
 *
 * <p>为什么要做成端点而不是"让脚本直接进容器跑 H2 工具"：镜像里只有 JRE（没有 jar/unzip 命令），
 * 而 H2 埋在 fat jar 深层；从应用内部发 {@code BACKUP TO} 用的正是同一个数据库引擎，
 * 既不需要额外装东西，也不会出现"备份工具的 H2 版本比应用新/旧"的坑。
 *
 * <p>安全口径：{@code maa.ops.token}（环境变量 {@code MAA_OPS_TOKEN}）没设 → 端点<b>当作不存在</b>返回 404，
 * 功能默认关闭。设了就必须带 {@code X-Ops-Token} 头，比对用定长比较（避免按字符提前返回泄露长度信息）。
 * 这个端点只读文件系统不删东西，最坏情况是"备份文件被塞满磁盘"，所以脚本侧还有保留策略兜着。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final BackupService backupService;
    private final String token;

    public OpsController(BackupService backupService,
                         @Value("${maa.ops.token:}") String token) {
        this.backupService = backupService;
        this.token = token == null ? "" : token.trim();
    }

    /**
     * 触发一次在线备份。
     *
     * @param tag 可选，存放目录名（默认当前时间戳）；由服务端净化后才参与拼路径
     */
    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> backup(
            @RequestHeader(value = "X-Ops-Token", required = false) String givenToken,
            @RequestBody(required = false) Map<String, String> body) {
        if (token.isEmpty()) {
            // 没配 token = 这个功能没开。返回 404 而不是 403：不告诉探测者"这里有个端点"
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        if (!tokenMatches(token, givenToken)) {
            return ResponseEntity.status(403).body(Map.of("error", "ops token 不正确"));
        }
        String tag = body == null ? null : body.get("tag");
        try {
            BackupService.Result r = backupService.backup(tag);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("tag", r.tag());
            ok.put("file", r.file());
            ok.put("bytes", r.bytes());
            ok.put("millis", r.millis());
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            // 失败必须说清楚：备份是"平时没人看、出事才用"的东西，静默失败等于没有备份
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    /** 定长比较：token 为空/不匹配都算失败 */
    static boolean tokenMatches(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }
}

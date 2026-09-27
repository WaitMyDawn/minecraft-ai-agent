package yagen.waitmydawn.maa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yagen.waitmydawn.maa.service.DbHealthProbe;
import yagen.waitmydawn.maa.service.DelegationTasks;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点。不带鉴权：它要被容器健康检查和外部监控探活，不能要求 token。
 *
 * <p>两种用途分得很清楚，别混：
 * <ul>
 *   <li><b>存活（默认）</b>：只要进程还能回话就 200。容器健康检查只看这个——数据库挂了
 *       不该触发容器重启，因为重启<b>治不好</b>一个损坏的库文件，只会变成崩溃循环；
 *       数据库的问题应该报警给人看。</li>
 *   <li><b>就绪（{@code ?strict=true}）</b>：数据库 down 时返回 503。给外部监控用，
 *       这样能第一时间收到"应用活着但用不了"的报警。</li>
 * </ul>
 *
 * <p>响应里只放"能自证活着"的字段：启动时刻、运行时长、数据库摘要、委派开关。
 * 不放内存、线程数这类只对你本机有意义的数字，也<b>不吐异常栈</b>——这个端点在公网上。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HealthController {

    private final DbHealthProbe dbProbe;
    private final DelegationTasks delegationTasks;
    private final Instant startedAt = Instant.now();

    public HealthController(DbHealthProbe dbProbe, DelegationTasks delegationTasks) {
        this.dbProbe = dbProbe;
        this.delegationTasks = delegationTasks;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestParam(name = "strict", defaultValue = "false") boolean strict) {
        String db = dbProbe.state();
        long checkedAt = dbProbe.checkedAtMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up".equals(db) ? "ok" : "degraded");
        body.put("startedAt", startedAt.toString());
        body.put("uptimeSec", Duration.between(startedAt, Instant.now()).toSeconds());
        body.put("db", db);
        // 秒级"多久之前探的"：绝对值对本机有意义，但看的人只关心新鲜度
        body.put("dbCheckedSecAgo",
                checkedAt == 0 ? null : (System.currentTimeMillis() - checkedAt) / 1000);
        if (!"up".equals(db)) body.put("dbError", dbProbe.lastError());
        body.put("delegation", delegationTasks.isEnabled());

        boolean degradedBlocks = strict && !"up".equals(db);
        return ResponseEntity.status(degradedBlocks ? 503 : 200).body(body);
    }
}

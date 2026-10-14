package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** 等待确认只保存有界文本；账号、会话和工作区绑定防止跨包续接，票据失效不会自动执行。 */
public final class PlanningClarifications {
    private record Pending(Long owner, Long conversation, String snapshot, String prompt, String question, boolean basicParametersOffered) {}
    private final Cache<String, Pending> pending;
    public PlanningClarifications() { this(com.github.benmanes.caffeine.cache.Ticker.systemTicker()); }
    PlanningClarifications(com.github.benmanes.caffeine.cache.Ticker ticker) {
        pending = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(Duration.ofMinutes(30)).build();
    }

    public synchronized String put(Long owner, Long conversation, Map<String, String> payload, String question) {
        return put(owner, conversation, payload, question, false);
    }
    public synchronized String put(Long owner, Long conversation, Map<String, String> payload, String question, boolean basicParametersOffered) {
        if (owner == null || question.length() > 4000 || payload.getOrDefault("prompt", "").length() > 8000)
            throw new IllegalArgumentException("待确认需求超过限额，请重新提交简短需求");
        pending.cleanUp();
        pending.asMap().entrySet().removeIf(entry -> Objects.equals(owner, entry.getValue().owner()));
        if (pending.asMap().size() >= 256) throw new IllegalArgumentException("待确认需求已满，请稍后重试");
        String ticket = UUID.randomUUID().toString();
        pending.put(ticket, new Pending(owner, conversation, snapshot(payload), payload.get("prompt"), question, basicParametersOffered));
        return ticket;
    }

    /** 只有任务成功入队才消费；队列拒绝不丢失原始主题及确认参数。 */
    public synchronized <T> T admit(String ticket, Long owner, Long conversation, Map<String, String> payload,
                                    Function<Map<String, String>, T> submit) {
        Pending value = pending.getIfPresent(ticket);
        if (value == null || !Objects.equals(owner, value.owner()) || !Objects.equals(conversation, value.conversation())
                || !snapshot(payload).equals(value.snapshot()))
            throw new IllegalArgumentException("待确认需求已过期或工作区已变化，请重新提交原始需求");
        String answer = payload.get("prompt");
        var next = new HashMap<>(payload);
        next.remove("clarificationTicket");
        next.remove("clarificationQuestion");
        next.remove("basicParametersOffered");
        if (!answer.matches("(?s)^\\s*(?:重新开始|取消构建|放弃本次需求).*")) {
            String merged = value.prompt() + "\n用户答复：" + answer;
            if (merged.length() > 8000) throw new IllegalArgumentException("补充后的需求超过限额，请重新提交完整需求");
            next.put("prompt", merged);
            next.put("clarificationQuestion", value.question());
            next.put("basicParametersOffered", String.valueOf(value.basicParametersOffered()));
        }
        T task = submit.apply(Map.copyOf(next));
        pending.invalidate(ticket);
        return task;
    }

    private static String snapshot(Map<String, String> payload) {
        var fields = new HashMap<>(payload);
        fields.remove("prompt");
        // 复用既有摘要字段，并纳入精确装配版本；请求密钥不进入缓存。
        fields.put("prompt", fields.getOrDefault("assembly", ""));
        return ReplacementSelections.snapshot(fields);
    }
}

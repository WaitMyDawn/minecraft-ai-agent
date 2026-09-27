package yagen.waitmydawn.maa.controller;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 失败的"按状态码"归类测试。
 *
 * <p>为什么要有它：旧实现是 `msg.contains("401")` 这种**文本匹配**，报错措辞一变就失效；
 * 现在改成读 DeepSeek 的状态码（langchain4j 1.18.1 把 401/429/5xx 映射成了带状态码的异常，
 * 而且对 429/5xx 打了 RetriableException、对 401 打了 NonRetriableException）。
 * 这里盯两件事：**文案能不能照着做**、**该不该重试**。
 */
class LlmErrorNoteTest {

    @Test
    @DisplayName("401：提示去设置页更新 Key，且不重试（重试也不会变好）")
    void unauthorized() {
        Throwable e = new AuthenticationException("Unauthorized");
        String note = ChatController.llmErrorNote(e);
        assertTrue(note.contains("401"), note);
        assertTrue(note.contains("设置"), "要指路：告诉用户去哪改 → " + note);
        assertFalse(ChatController.llmRetriable(e), "401 不该重试");
    }

    @Test
    @DisplayName("429：如实说被限流 + 会重试")
    void rateLimited() {
        Throwable e = new RateLimitException("Rate limit reached");
        String note = ChatController.llmErrorNote(e);
        assertTrue(note.contains("429"), note);
        assertTrue(ChatController.llmRetriable(e), "429 必须重试（退避后往往就过了）");
    }

    @Test
    @DisplayName("5xx：说服务端故障 + 会重试；402：说余额不足 + 不重试")
    void serverErrorAndPayment() {
        assertTrue(ChatController.llmRetriable(new InternalServerException("boom")));
        assertTrue(ChatController.llmRetriable(new HttpException(503, "Service Unavailable")),
                "没被映射成具体类型的 5xx 也要能按状态码重试");
        assertTrue(ChatController.llmErrorNote(new HttpException(503, "x")).contains("503"));

        Throwable pay = new HttpException(402, "Insufficient Balance");
        assertTrue(ChatController.llmErrorNote(pay).contains("余额"), ChatController.llmErrorNote(pay));
        assertFalse(ChatController.llmRetriable(pay), "余额不足重试也没用");
    }

    @Test
    @DisplayName("超时：可重试且文案区别于服务端故障")
    void timeout() {
        Throwable e = new TimeoutException("timeout");
        assertTrue(ChatController.llmErrorNote(e).contains("超时"));
        assertTrue(ChatController.llmRetriable(e));
    }

    @Test
    @DisplayName("认不出来的异常返回 null：交给上层旧逻辑，不能瞎归类")
    void unknownGoesBackToCaller() {
        assertNull(ChatController.llmErrorNote(new IllegalStateException("别的东西炸了")));
        assertFalse(ChatController.llmRetriable(new IllegalStateException("x")));
        assertNull(ChatController.llmErrorNote(null));
    }
}

package yagen.waitmydawn.maa.service;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * token 用量与调用次数采集器（过程层指标）。
 *
 * <p>背景：项目的 "成本层" 此前只能报耗时、报不出 token——LangChain4j 的 AiServices
 * 只把模型输出的文本交给调用方，用量信息被丢弃。这里挂到 {@link ChatModelListener} 上，
 * 每次模型响应（含工具调用的多轮往返）都把用量累加进来。
 *
 * <p>线程安全：一次请求内可能并发调用，计数器用 AtomicInteger。
 */
public class TokenUsageCollector implements ChatModelListener {

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();

    @Override
    public void onResponse(ChatModelResponseContext context) {
        modelCalls.incrementAndGet();
        TokenUsage usage = context.chatResponse() == null ? null : context.chatResponse().tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null) inputTokens.addAndGet(usage.inputTokenCount());
            if (usage.outputTokenCount() != null) outputTokens.addAndGet(usage.outputTokenCount());
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        // 失败调用也计入次数，避免"重试了很多次但报表上看不出来"
        modelCalls.incrementAndGet();
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int inputTokens() {
        return inputTokens.get();
    }

    public int outputTokens() {
        return outputTokens.get();
    }
}

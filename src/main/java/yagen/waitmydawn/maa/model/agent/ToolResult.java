package yagen.waitmydawn.maa.model.agent;

import java.util.List;

/** Java 生成的工具执行回执；operationId 来自本轮执行器，不能由模型赋予幂等权威。 */
public record ToolResult(int schemaVersion, String operationId, Tool tool, Status status,
                         String message, List<String> applied, List<Issue> issues) {
    public enum Tool { SET_ENVIRONMENT, REMOVE_MODS, ADJUST_CATEGORY_TARGETS, SET_TARGET_COUNT, SET_MAX_DOWNLOADS, SET_PACK_NAME }
    public enum Status { APPLIED, NO_CHANGE, PARTIAL, REJECTED, UNAVAILABLE }
    public enum IssueCode { INVALID_ARGUMENT, UNKNOWN_PROJECT, UNSUPPORTED_ENVIRONMENT, UPSTREAM_UNAVAILABLE, LIMIT_REACHED }
    public record Issue(String input, IssueCode code, String message) {
        public Issue {
            input = ContractRules.text(input, "issues.input", 0, ContractRules.MAX_REASON);
            ContractRules.required(code, "issues.code");
            message = ContractRules.text(message, "issues.message", 1, ContractRules.MAX_REASON);
        }
    }

    public ToolResult {
        ContractRules.version(schemaVersion);
        operationId = ContractRules.text(operationId, "operationId", 1, 128);
        ContractRules.require(operationId.matches("[A-Za-z0-9._-]{1,128}"), "operationId");
        ContractRules.required(tool, "tool");
        ContractRules.required(status, "status");
        message = ContractRules.text(message, "message", 1, ContractRules.MAX_REPLY);
        applied = ContractRules.list(applied, "applied", ContractRules.MAX_MODS);
        applied.forEach(value -> ContractRules.text(value, "applied", 1, ContractRules.MAX_REASON));
        issues = ContractRules.list(issues, "issues", ContractRules.MAX_MODS);
        switch (status) {
            case APPLIED -> ContractRules.require(!applied.isEmpty() && issues.isEmpty(), "APPLIED");
            case PARTIAL -> ContractRules.require(!applied.isEmpty() && !issues.isEmpty(), "PARTIAL");
            case NO_CHANGE -> ContractRules.require(applied.isEmpty() && issues.isEmpty(), "NO_CHANGE");
            case REJECTED, UNAVAILABLE -> ContractRules.require(applied.isEmpty() && !issues.isEmpty(), "status/issues");
        }
    }
}

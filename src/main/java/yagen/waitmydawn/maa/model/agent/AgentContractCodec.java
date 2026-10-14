package yagen.waitmydawn.maa.model.agent;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 严格解析边界；除显式历史迁移字段外，缺字段、未知字段和类型强制转换均拒绝。 */
public final class AgentContractCodec {
    public enum FailureKind { INPUT_LIMIT, JSON_SYNTAX, STRUCTURE, SEMANTIC }
    public record Failure(String contract, FailureKind kind, String field) {}
    /** 不挂接原始异常 cause，避免异常链携带模型内容或未知属性名。 */
    public static final class ValidationException extends IllegalArgumentException {
        private final Failure failure;
        private ValidationException(Failure failure) {
            super("Agent 结构化输出无效: " + failure.contract() + ":" + failure.kind() + ":" + failure.field());
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }
    private static final java.util.Set<String> KNOWN_FIELDS = java.util.Set.of(
            "schemaVersion", "mode", "reply", "name", "environment", "mcVersion", "loader", "changeRequested",
            "min", "max", "requirements", "criteria", "id", "source", "kind", "projects", "capabilities", "addonCores", "searches", "preferences", "popularity", "freshness", "releaseRequirements", "releaseCapabilities",
            "count", "value", "scope", "precision", "mustHave", "add", "remove", "exclude", "expandAddons",
            "replacementPolicy", "searchIntents", "category", "weight", "keywords", "excludedCategories", "maxDownloads",
            "constraintChanges", "clearCount", "allowSlugs", "allowCategories", "replacementPolicyChanged", "maxDownloadsChanged", "approved", "rejected",
            "slug", "reason", "explanation", "allowBackfill", "evidence", "quote", "purpose", "gap", "status", "message", "operationId", "tool", "applied", "issues", "code");
    private static final int MAX_JSON_LENGTH = 1_048_576;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
                config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer, tools.jackson.databind.cfg.CoercionAction.Fail);
                config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionAction.Fail);
                config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean, tools.jackson.databind.cfg.CoercionAction.Fail);
            })
            .build();

    public BuildIntent readBuildIntent(String json) {
        checkInput(json, BuildIntent.class);
        tools.jackson.databind.JsonNode tree;
        try {
            tree = mapper.readTree(json);
            migrate(tree);
            if (tree instanceof tools.jackson.databind.node.ObjectNode object && !object.has("constraintChanges")) {
                // v1 历史响应没有解除操作；唯一兼容缺省只授予继承权限。
                object.set("constraintChanges", mapper.valueToTree(ConstraintChanges.KEEP));
            }
        } catch (RuntimeException e) {
            throw failure(BuildIntent.class, FailureKind.JSON_SYNTAX, "json");
        }
        if (tree != null && tree.isObject()) for (var field : BuildIntent.class.getRecordComponents()) {
            if (!tree.has(field.getName())) throw failure(BuildIntent.class, FailureKind.STRUCTURE, field.getName());
        }
        return read(mapper.writeValueAsString(tree), BuildIntent.class);
    }
    public BuildConstraints readConstraints(String json) {
        checkInput(json, BuildConstraints.class);
        try {
            var tree = mapper.readTree(json);
            migrate(tree);
            return read(mapper.writeValueAsString(tree), BuildConstraints.class);
        } catch (ValidationException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw failure(BuildConstraints.class, FailureKind.JSON_SYNTAX, "json"); }
    }
    private void migrate(tools.jackson.databind.JsonNode tree) {
        // 历史需求没有逐项检索计划；空计划保留旧语义，不编造用途或授予新增权限。
        if (tree != null && tree.path("requirements").isArray()) for (var entry : tree.path("requirements"))
            if (entry instanceof tools.jackson.databind.node.ObjectNode requirement) {
                if (!requirement.has("searches")) requirement.putArray("searches");
                if (!requirement.has("preferences")) requirement.set("preferences", mapper.valueToTree(SelectionPreferences.DEFAULT));
                for (var child : requirement.path("criteria"))
                    if (child instanceof tools.jackson.databind.node.ObjectNode criterion && !criterion.has("preferences"))
                        criterion.set("preferences", mapper.valueToTree(SelectionPreferences.fromSource(criterion.path("source").asText())));
                if (tree.path("schemaVersion").asInt() < 3 && !requirement.has("criteria")) requirement.putArray("criteria");
            }
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object) || tree.path("schemaVersion").asInt() != 1) return;
        if (!object.has("requirements")) object.putArray("requirements");
        if (object.get("constraintChanges") instanceof tools.jackson.databind.node.ObjectNode changes) {
            if (!changes.has("releaseRequirements")) changes.putArray("releaseRequirements");
            if (!changes.has("releaseCapabilities")) changes.putArray("releaseCapabilities");
        }
        if (object.get("count") instanceof tools.jackson.databind.node.ObjectNode count
                && count.has("value") && !count.has("min") && !count.has("max")) {
            count.set("min", count.get("value"));
            count.set("max", count.get("value"));
        }
    }
    public ReviewDecision readReviewDecision(String json) { return read(json, ReviewDecision.class); }
    public ReviewResponse readReviewResponse(String json) { return read(json, ReviewResponse.class); }
    public ToolResult readToolResult(String json) { return read(json, ToolResult.class); }

    public String write(Object contract) {
        ContractRules.require(contract instanceof BuildIntent || contract instanceof BuildConstraints || contract instanceof ReviewDecision || contract instanceof ToolResult, "contractType");
        return yagen.waitmydawn.maa.service.MessageJson.escape(mapper.writeValueAsString(contract));
    }

    private <T> T read(String json, Class<T> type) {
        checkInput(json, type);
        try {
            var tree = mapper.readTree(json);
            if ((type == ReviewDecision.class || type == ReviewResponse.class)
                    && tree instanceof tools.jackson.databind.node.ObjectNode object && !object.has("requirements"))
                object.putArray("requirements");
            if ((type == ReviewDecision.class || type == ReviewResponse.class) && tree.path("requirements").isArray())
                for (var entry : tree.path("requirements")) if (entry instanceof tools.jackson.databind.node.ObjectNode requirement
                        && !requirement.has("criteria")) requirement.putArray("criteria");
            T result = mapper.treeToValue(tree, type);
            return ContractRules.required(result, "root");
        } catch (RuntimeException e) {
            // 不附带模型原文，避免解析异常把输入内容带入接口错误或诊断日志。
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ContractRules.Violation violation) {
                    throw failure(type, FailureKind.SEMANTIC, violation.field);
                }
            }
            String field = "jsonStructure";
            if (e instanceof tools.jackson.core.JacksonException jackson && !jackson.getPath().isEmpty()) {
                var path = new java.util.ArrayList<String>();
                for (var ref : jackson.getPath()) {
                    String name = ref.getPropertyName();
                    if (name == null) continue;
                    if (!KNOWN_FIELDS.contains(name) || path.size() >= 8) { path.clear(); path.add("unknown"); break; }
                    path.add(name);
                }
                if (!path.isEmpty()) field = String.join(".", path);
            }
            throw failure(type, FailureKind.STRUCTURE, field);
        }
    }
    private static void checkInput(String json, Class<?> type) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_LENGTH) throw failure(type, FailureKind.INPUT_LIMIT, "json");
    }
    private static ValidationException failure(Class<?> type, FailureKind kind, String field) {
        return new ValidationException(new Failure(type.getSimpleName(), kind, field));
    }
}

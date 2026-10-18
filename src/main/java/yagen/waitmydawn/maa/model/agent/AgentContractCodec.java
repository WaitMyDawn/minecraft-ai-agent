package yagen.waitmydawn.maa.model.agent;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 严格解析边界；除显式历史迁移字段外，缺字段、未知字段和类型强制转换均拒绝。 */
public final class AgentContractCodec {
    public enum FailureKind { INPUT_LIMIT, JSON_SYNTAX, STRUCTURE, SEMANTIC }
    public record Failure(String contract, FailureKind kind, String field, String pointer, String guidance) {
        public Failure(String contract, FailureKind kind, String field) { this(contract, kind, field, "", ""); }
    }
    /** 不挂接原始异常 cause，避免异常链携带模型内容或未知属性名。 */
    public static final class ValidationException extends IllegalArgumentException {
        private final Failure failure;
        private ValidationException(Failure failure) {
            super("Agent 结构化输出无效: " + failure.contract() + ":" + failure.kind() + ":" + failure.field()
                    + (failure.pointer().isEmpty() ? "" : "；位置 " + failure.pointer())
                    + (failure.guidance().isEmpty() ? "" : "；" + failure.guidance()));
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
    /** 用途报告与选模权限分别校验；只从完整、无重复键的 JSON 中隔离已知报告字段。 */
    public ReviewResponse readReviewSelection(String json) {
        checkInput(json, ReviewResponse.class);
        tools.jackson.databind.JsonNode tree;
        try { tree = mapper.readTree(json); }
        catch (RuntimeException invalid) { throw failure(ReviewResponse.class, FailureKind.JSON_SYNTAX, "json"); }
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object))
            throw failure(ReviewResponse.class, FailureKind.STRUCTURE, "root");
        object.remove("requirements");
        return read(mapper.writeValueAsString(object), ReviewResponse.class);
    }
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
            if (type == ReviewDecision.class || type == ReviewResponse.class) checkReviewEvidence(tree, type);
            if (type == BuildIntent.class) {
                checkBuildSearches(tree, type);
                if (java.util.Set.of("NEW", "EMPTY").contains(tree.path("mode").asText()) && !tree.path("count").isObject())
                    throw new ValidationException(new Failure(type.getSimpleName(), FailureKind.SEMANTIC, "count", "/count",
                            "NEW/EMPTY 的 count 必须是对象；仅 EDIT/CLARIFY/REJECT 可为 null。仅点名集合的新建可用其去重大小作为 ROOT_MODS 单值目标，必要前置另计；不要改动用户明确给出的数量。"));
            }
            T result = mapper.treeToValue(tree, type);
            return ContractRules.required(result, "root");
        } catch (ValidationException invalid) { throw invalid; }
        catch (RuntimeException e) {
            // 不附带模型原文，避免解析异常把输入内容带入接口错误或诊断日志。
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ContractRules.Violation violation) {
                    throw failure(type, FailureKind.SEMANTIC, violation.field);
                }
            }
            String field = "jsonStructure";
            var pointer = new java.util.ArrayList<String>();
            if (e instanceof tools.jackson.core.JacksonException jackson && !jackson.getPath().isEmpty()) {
                var path = new java.util.ArrayList<String>();
                for (var ref : jackson.getPath()) {
                    String name = ref.getPropertyName();
                    if (name == null) {
                        if (ref.getIndex() >= 0 && ref.getIndex() <= 10000) pointer.add(String.valueOf(ref.getIndex()));
                        continue;
                    }
                    if (!KNOWN_FIELDS.contains(name) || path.size() >= 8) { path.clear(); path.add("unknown"); pointer.clear(); pointer.add("unknown"); break; }
                    path.add(name);
                    pointer.add(name);
                }
                if (!path.isEmpty()) field = String.join(".", path);
            }
            String guidance = field.equals("requirements.criteria.capabilities")
                    ? "criteria 子目标没有 capabilities 字段；只允许 source/kind/projects/addonCores/searches/preferences；能力提示放在父 requirements.capabilities"
                    : "字段名、类型及枚举值须符合 JSON Schema，不能用空值或额外字段代替";
            if ((type == ReviewResponse.class || type == ReviewDecision.class) && field.startsWith("requirements.criteria.")
                    && !field.substring("requirements.criteria.".length()).contains(".")
                    && !java.util.Set.of("source", "evidence", "gap").contains(field.substring("requirements.criteria.".length())))
                guidance = "审核子目标只允许 source/evidence/gap，不允许嵌套 criteria 或复制父项 id、规划字段；移除多余字段并保留对应证据";
            if (type == BuildIntent.class && field.equals("requirements.capabilities"))
                guidance = "capabilities 只能使用 " + java.util.Arrays.toString(BuildRequirement.Capability.values())
                        + "；未列入枚举的玩法保留 source 和 searches，capabilities 可为空，不能创造新枚举";
            throw new ValidationException(new Failure(type.getSimpleName(), FailureKind.STRUCTURE, field,
                    pointer.isEmpty() ? "" : "/" + String.join("/", pointer), guidance));
        }
    }
    private static void checkReviewEvidence(tools.jackson.databind.JsonNode tree, Class<?> type) {
        if (!tree.path("requirements").isArray()) return;
        var issues = new java.util.ArrayList<Failure>();
        for (int i = 0; i < tree.path("requirements").size(); i++) {
            var requirement = tree.path("requirements").get(i);
            String parent = "/requirements/" + i;
            checkRequiredReviewFields(requirement, RequirementReview.class, type, "requirements", parent, issues);
            addIssue(issues, evidenceLimit(requirement.path("evidence"), type, "requirements.evidence", parent + "/evidence", RequirementReview.MAX_EVIDENCE));
            if (!requirement.path("criteria").isArray()) continue;
            for (int j = 0; j < requirement.path("criteria").size(); j++) {
                var criterion = requirement.path("criteria").get(j);
                String pointer = parent + "/criteria/" + j;
                checkRequiredReviewFields(criterion, CriterionReview.class, type, "requirements.criteria", pointer, issues);
                addIssue(issues, evidenceLimit(criterion.path("evidence"), type, "criteria.evidence",
                        pointer + "/evidence", CriterionReview.MAX_EVIDENCE));
            }
        }
        if (issues.isEmpty()) return;
        var first = issues.getFirst();
        String guidance = issues.stream().map(issue -> issue.pointer() + "：" + issue.guidance())
                .collect(java.util.stream.Collectors.joining("；"))
                + "；source/evidence/gap 均须出现；有 criteria 的父项必须 evidence=[]、gap=\"\"；每个规划 source 只写一条，不得拆分或新增 source 规避证据上限；其余字段也按 Schema 检查";
        throw new ValidationException(new Failure(type.getSimpleName(), first.kind(), first.field(), first.pointer(), guidance));
    }
    private static void checkRequiredReviewFields(tools.jackson.databind.JsonNode node, Class<?> record, Class<?> contract,
                                                   String field, String pointer, java.util.List<Failure> issues) {
        if (!node.isObject()) return;
        for (var component : record.getRecordComponents()) {
            var value = node.path(component.getName());
            boolean text = component.getType() == String.class;
            if (value.isMissingNode() || text && !value.isTextual() || component.getType() == java.util.List.class && !value.isArray())
                addIssue(issues, new Failure(contract.getSimpleName(), FailureKind.STRUCTURE, field + "." + component.getName(),
                        pointer + "/" + component.getName(), component.getName() + " 是必填" + (text ? "字符串" : "数组")
                        + "，不能省略或使用 null；无缺口时 gap 填空字符串"));
        }
    }
    private static void addIssue(java.util.List<Failure> issues, Failure issue) {
        if (issue != null && issues.size() < 8) issues.add(issue);
    }
    private static void checkBuildSearches(tools.jackson.databind.JsonNode tree, Class<?> type) {
        checkSearchCategories(tree.path("searchIntents"), type, "searchIntents", "/searchIntents");
        if (!tree.path("requirements").isArray()) return;
        for (int i = 0; i < tree.path("requirements").size(); i++) {
            var requirement = tree.path("requirements").get(i);
            String parent = "/requirements/" + i;
            checkSearchCategories(requirement.path("searches"), type, "requirements.searches", parent + "/searches");
            if (!requirement.path("criteria").isArray()) continue;
            for (int j = 0; j < requirement.path("criteria").size(); j++)
                checkSearchCategories(requirement.path("criteria").get(j).path("searches"), type, "criteria.searches",
                        parent + "/criteria/" + j + "/searches");
        }
    }
    private static void checkSearchCategories(tools.jackson.databind.JsonNode values, Class<?> type, String field, String pointer) {
        if (!values.isArray()) return;
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            var category = values.get(i).path("category");
            if (category.isTextual() && !seen.add(category.asText()))
                throw new ValidationException(new Failure(type.getSimpleName(), FailureKind.SEMANTIC, field, pointer + "/" + i + "/category",
                        "同一检索数组的 category 只能出现一次；合并同类别关键词，每项 keywords 最多 " + ContractRules.MAX_KEYWORDS
                                + " 个，保留最相关词；同时检查 searchIntents、父项 searches 与所有 criteria.searches，不创建新类别规避重复"));
        }
    }
    private static Failure evidenceLimit(tools.jackson.databind.JsonNode values, Class<?> type, String field, String pointer, int maximum) {
        if (values.isArray() && values.size() > maximum)
            return new Failure(type.getSimpleName(), FailureKind.SEMANTIC, field, pointer,
                    "该 evidence 数组最多 " + maximum + " 项，当前 " + values.size()
                            + " 项；保留最直接的用途依据，approved/rejected 与证据数量分别遵守各自限制");
        return null;
    }
    private static void checkInput(String json, Class<?> type) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_LENGTH) throw failure(type, FailureKind.INPUT_LIMIT, "json");
    }
    private static ValidationException failure(Class<?> type, FailureKind kind, String field) {
        return new ValidationException(new Failure(type.getSimpleName(), kind, field));
    }
}

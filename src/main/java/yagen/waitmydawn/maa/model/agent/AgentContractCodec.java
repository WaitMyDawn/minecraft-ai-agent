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
            "quantity", "count", "value", "scope", "precision", "mustHave", "add", "remove", "exclude", "expandAddons",
            "replacementPolicy", "searchIntents", "category", "weight", "keywords", "excludedCategories", "maxDownloads",
            "constraintChanges", "clearCount", "allowSlugs", "allowCategories", "replacementPolicyChanged", "maxDownloadsChanged", "approved", "rejected",
            "slug", "reason", "explanation", "allowBackfill", "authority", "targetId", "requestQuote", "relatedSlug", "backfill", "basis", "evidence", "quote", "purpose", "gap", "status", "message", "operationId", "tool", "applied", "issues", "code");
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
                if (!requirement.has("quantity")) requirement.set("quantity", mapper.valueToTree(requirement.path("criteria").isEmpty() && localQuantityTarget(requirement)
                        ? RequirementQuantity.fromSource(requirement.path("source").asText()) : null));
                if (!requirement.has("searches")) requirement.putArray("searches");
                if (!requirement.has("preferences")) requirement.set("preferences", mapper.valueToTree(SelectionPreferences.DEFAULT));
                for (var child : requirement.path("criteria"))
                    if (child instanceof tools.jackson.databind.node.ObjectNode criterion && !criterion.has("quantity"))
                        criterion.set("quantity", mapper.valueToTree(localQuantityTarget(criterion) ? RequirementQuantity.fromSource(criterion.path("source").asText()) : null));
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
    private boolean localQuantityTarget(tools.jackson.databind.JsonNode target) {
        return !target.path("kind").asText().equals("CHANGE")
                && (!target.path("kind").asText().equals("GENERAL") || !target.path("searches").isEmpty());
    }
    public ReviewDecision readReviewDecision(String json) { return read(json, ReviewDecision.class); }
    public ReviewResponse readReviewResponse(String json) { return read(json, ReviewResponse.class); }
    private void migrateReview(tools.jackson.databind.JsonNode tree) {
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object) || !tree.path("schemaVersion").isIntegralNumber()
                || tree.path("schemaVersion").asInt() != 1) return;
        // v1 缺少作用域和来源，保持原有全包否决及补位语义，不能由缺省迁移授予新权限。
        if (!object.has("backfill")) object.set("backfill", mapper.valueToTree(ReviewDecision.Backfill.LEGACY));
        if (!object.path("backfill").equals(mapper.valueToTree(ReviewDecision.Backfill.LEGACY)))
            throw failure(ReviewResponse.class, FailureKind.SEMANTIC, "backfill.version");
        for (var item : object.path("rejected")) if (item instanceof tools.jackson.databind.node.ObjectNode rejection) {
            if (!rejection.has("authority")) rejection.set("authority", mapper.valueToTree(ReviewDecision.Authority.LEGACY));
            if (!rejection.path("authority").equals(mapper.valueToTree(ReviewDecision.Authority.LEGACY)))
                throw failure(ReviewResponse.class, FailureKind.SEMANTIC, "authority.version");
        }
    }
    public record FragmentIssue(String pointer, String code) {}
    public record ReviewFragments(java.util.List<RequirementReview> requirements, java.util.Set<String> vetoes,
                                  java.util.Set<String> contradictoryVetoes, boolean backfillForbidden, java.util.List<FragmentIssue> issues,
                                  int schemaVersion, java.util.List<ReviewDecision.Rejection> rejections,
                                  ReviewDecision.Backfill backfill, java.util.Set<String> approvals) {}

    /** 完整JSON中的报告片段和限制可独立验证；片段恢复不授予批准或补位权限。 */
    public ReviewFragments readReviewFragments(String json, java.util.Set<String> candidates) {
        checkInput(json, ReviewResponse.class);
        var tree = mapper.readTree(json);
        migrateReview(tree);
        var fields = java.util.Set.of("schemaVersion", "approved", "rejected", "allowBackfill", "requirements", "backfill");
        if (!tree.isObject() || tree.propertyStream().anyMatch(e -> !fields.contains(e.getKey()))
                || !tree.path("schemaVersion").isIntegralNumber() || !java.util.Set.of("1", "2").contains(tree.path("schemaVersion").asText())
                || !tree.path("approved").isArray() || tree.path("approved").size() > ContractRules.MAX_MODS
                || !tree.path("rejected").isArray() || tree.path("rejected").size() > ContractRules.MAX_MODS
                || !tree.path("allowBackfill").isBoolean())
            throw failure(ReviewResponse.class, FailureKind.STRUCTURE, "fragments.root");
        var issues = new java.util.ArrayList<FragmentIssue>();
        var vetoes = new java.util.LinkedHashSet<String>();
        var rejections = new java.util.ArrayList<ReviewDecision.Rejection>();
        for (int i = 0; i < tree.path("rejected").size(); i++) {
            try {
                var veto = mapper.treeToValue(tree.path("rejected").get(i), ReviewDecision.Rejection.class);
                if (candidates.contains(veto.slug())) { rejections.add(veto); if (veto.global()) vetoes.add(veto.slug()); }
                else fragmentIssue(issues, "/rejected/" + i, "OUTSIDE_CANDIDATES");
            } catch (RuntimeException invalid) {
                fragmentIssue(issues, "/rejected/" + i, "INVALID_REJECTION");
                String ref = tree.path("rejected").get(i).path("slug").asText();
                if (candidates.contains(ref)) vetoes.add(ref);
            }
        }
        var rows = new java.util.ArrayList<RequirementReview>();
        var contradictory = new java.util.LinkedHashSet<String>();
        var approvals = new java.util.LinkedHashSet<String>();
        for (var approved : tree.path("approved"))
            if (approved.isTextual()) {
                if (candidates.contains(approved.asText())) approvals.add(approved.asText());
                if (vetoes.contains(approved.asText())) contradictory.add(approved.asText());
            }
        ReviewDecision.Backfill backfill = null;
        try { backfill = mapper.treeToValue(tree.path("backfill"), ReviewDecision.Backfill.class); }
        catch (RuntimeException invalid) { fragmentIssue(issues, "/backfill", "INVALID_BACKFILL"); }
        var input = tree.path("requirements");
        if (input.isMissingNode()) return new ReviewFragments(java.util.List.of(), java.util.Set.copyOf(vetoes),
                java.util.Set.copyOf(contradictory), !tree.path("allowBackfill").asBoolean(), java.util.List.copyOf(issues),
                tree.path("schemaVersion").asInt(), java.util.List.copyOf(rejections), backfill, java.util.Set.copyOf(approvals));
        if (!input.isArray() || input.size() > 64) {
            fragmentIssue(issues, "/requirements", "INVALID_REPORT");
        } else {
            int totalChildren = 0;
            var ids = new java.util.HashMap<String, Integer>();
            for (var row : input) ids.merge(row.path("id").asText(), 1, Integer::sum);
            for (int i = 0; i < input.size(); i++) {
                var row = input.get(i);
                String pointer = "/requirements/" + i;
                if (!row.isObject() || ids.getOrDefault(row.path("id").asText(), 0) != 1) {
                    fragmentIssue(issues, pointer, "INVALID_OR_DUPLICATE_REQUIREMENT"); continue;
                }
                try {
                    var base = ((tools.jackson.databind.node.ObjectNode) row).deepCopy();
                    var children = base.remove("criteria");
                    base.putArray("criteria");
                    var parent = mapper.treeToValue(base, RequirementReview.class);
                    if (children == null) children = mapper.createArrayNode();
                    if (!children.isArray() || children.size() > 16 || (totalChildren += children.size()) > 128) {
                        fragmentIssue(issues, pointer + "/criteria", "INVALID_CRITERIA"); continue;
                    }
                    var sources = new java.util.HashMap<String, Integer>();
                    for (var child : children) sources.merge(child.path("source").asText(), 1, Integer::sum);
                    var kept = new java.util.ArrayList<CriterionReview>();
                    for (int j = 0; j < children.size(); j++) {
                        try {
                            var child = mapper.treeToValue(children.get(j), CriterionReview.class);
                            if (sources.getOrDefault(child.source(), 0) != 1) throw new IllegalArgumentException();
                            kept.add(child);
                        } catch (RuntimeException invalid) { fragmentIssue(issues, pointer + "/criteria/" + j, "INVALID_CRITERION"); }
                    }
                    rows.add(new RequirementReview(parent.id(), parent.evidence(), parent.gap(), kept));
                } catch (RuntimeException invalid) { fragmentIssue(issues, pointer, "INVALID_REQUIREMENT"); }
            }
        }
        return new ReviewFragments(java.util.List.copyOf(rows), java.util.Set.copyOf(vetoes),
                java.util.Set.copyOf(contradictory), !tree.path("allowBackfill").asBoolean(), java.util.List.copyOf(issues),
                tree.path("schemaVersion").asInt(), java.util.List.copyOf(rejections), backfill, java.util.Set.copyOf(approvals));
    }
    private static void fragmentIssue(java.util.List<FragmentIssue> issues, String pointer, String code) {
        if (issues.size() < 128) issues.add(new FragmentIssue(pointer, code));
    }
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
    /** 用途报告修正不重新授权选模；包含顺序和拒绝说明在内的权限字段须保持不变。 */
    public void requireSameReviewSelection(String original, String repaired) {
        var before = readReviewSelection(original);
        var after = readReviewSelection(repaired);
        if (!before.approved().equals(after.approved()) || !before.rejected().equals(after.rejected())
                || before.allowBackfill() != after.allowBackfill() || !before.backfill().equals(after.backfill())
                || before.schemaVersion() != after.schemaVersion())
            throw new ValidationException(new Failure("ReviewResponse", FailureKind.SEMANTIC, "repair.selection", "",
                    "仅修正requirements时不得改变schemaVersion、approved、rejected/authority、allowBackfill或backfill；保留原选模权限"));
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
            if (type == ReviewDecision.class || type == ReviewResponse.class) migrateReview(tree);
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
            int repeatedApprovals = type == ReviewResponse.class ? normalizeApprovals(tree) : 0;
            if (type == ReviewResponse.class) normalizeRejections(tree);
            T result = mapper.treeToValue(tree, type);
            if (repeatedApprovals > 0) yagen.waitmydawn.maa.logging.MaaLog.app(
                    "Critic 批准列表规范化：合并 " + repeatedApprovals + " 个重复引用，仍执行候选、否决及证据校验");
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

    /** 仅完全相同的批准引用可幂等合并；无效条目及否决仍严格校验。 */
    private int normalizeApprovals(tools.jackson.databind.JsonNode tree) {
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object)
                || !(object.get("approved") instanceof tools.jackson.databind.node.ArrayNode approved)
                || approved.size() > ContractRules.MAX_MODS) return 0;
        var unique = new java.util.LinkedHashSet<String>();
        for (var value : approved) {
            if (!value.isTextual()) return 0;
            unique.add(value.asText());
        }
        int duplicates = approved.size() - unique.size();
        if (duplicates > 0) object.set("approved", mapper.valueToTree(java.util.List.copyOf(unique)));
        return duplicates;
    }
    private void normalizeRejections(tools.jackson.databind.JsonNode tree) {
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object)
                || !tree.path("rejected").isArray() || tree.path("rejected").size() > ContractRules.MAX_MODS) return;
        var unique = new java.util.LinkedHashSet<tools.jackson.databind.JsonNode>();
        tree.path("rejected").forEach(unique::add);
        int removed = tree.path("rejected").size() - unique.size();
        if (removed > 0) {
            object.set("rejected", mapper.valueToTree(unique));
            yagen.waitmydawn.maa.logging.MaaLog.app("Critic 否决列表规范化：合并 " + removed + " 个完全相同的条目，仍执行候选及互斥校验");
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

package yagen.waitmydawn.maa.model.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.CategoryRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** JSON Schema 的字段、枚举和边界复用 Java 契约；跨字段及实时事实由 Java 单独校验。 */
public final class AgentSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AgentSchemas() {}

    public static ObjectNode buildIntent() {
        ObjectNode environment = object(BuildIntent.Environment.class,
                "mcVersion", text(1, 32).put("pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$"),
                "loader", enumeration(BuildIntent.Loader.class), "changeRequested", type("boolean"));
        ObjectNode count = object(BuildIntent.CountTarget.class,
                "value", integer(0, ContractRules.MAX_MODS), "scope", enumeration(BuildIntent.CountScope.class),
                "precision", enumeration(BuildIntent.Precision.class), "min", integer(0, ContractRules.MAX_MODS),
                "max", integer(0, ContractRules.MAX_MODS));
        ObjectNode intent = object(BuildIntent.SearchIntent.class,
                "category", strings(CategoryRegistry.CATEGORIES), "weight", integer(1, 100),
                "keywords", array(text(1, 80), 1, ContractRules.MAX_KEYWORDS, true));
        ObjectNode preferences = object(SelectionPreferences.class,
                "popularity", enumeration(SelectionPreferences.Popularity.class),
                "freshness", enumeration(SelectionPreferences.Freshness.class));
        ObjectNode criterion = object(RequirementCriterion.class, "source", text(1, 4000),
                "kind", enumeration(BuildRequirement.Kind.class), "projects", projects(), "addonCores", projects(),
                "searches", array(intent, 0, 3, false), "preferences", preferences);
        ObjectNode requirement = object(BuildRequirement.class,
                "id", text(1, 64).put("pattern", "^r-[a-f0-9]{16}$"), "source", text(1, 4000),
                "kind", enumeration(BuildRequirement.Kind.class), "projects", projects(),
                "capabilities", array(enumeration(BuildRequirement.Capability.class), 0, BuildRequirement.Capability.values().length, true),
                "addonCores", projects(), "searches", array(intent, 0, 3, false), "preferences", preferences,
                "criteria", array(criterion, 0, 16, false));
        ObjectNode changes = object(ConstraintChanges.class, "allowSlugs", projects(),
                "allowCategories", array(strings(CategoryRegistry.CATEGORIES), 0, ContractRules.MAX_SEARCH_INTENTS, true),
                "clearCount", type("boolean"), "replacementPolicyChanged", type("boolean"), "maxDownloadsChanged", type("boolean"),
                "releaseRequirements", array(text(1, 64).put("pattern", "^r-[a-f0-9]{16}$"), 0, 64, true),
                "releaseCapabilities", array(enumeration(BuildRequirement.Capability.class), 0, BuildRequirement.Capability.values().length, true));
        return root("maa-build-intent-v3", object(BuildIntent.class,
                "schemaVersion", type("integer").put("const", 3), "mode", enumeration(BuildIntent.Mode.class),
                "reply", text(0, ContractRules.MAX_REPLY), "name", text(0, 60),
                "environment", nullable(environment), "count", nullable(count),
                "mustHave", projects(), "add", projects(), "remove", projects(), "exclude", projects(),
                "expandAddons", projects(), "replacementPolicy", enumeration(BuildIntent.ReplacementPolicy.class),
                "searchIntents", array(intent, 0, ContractRules.MAX_SEARCH_INTENTS, false),
                "excludedCategories", array(strings(CategoryRegistry.CATEGORIES), 0, ContractRules.MAX_SEARCH_INTENTS, true),
                "maxDownloads", integer(0, Long.MAX_VALUE), "constraintChanges", changes,
                "requirements", array(requirement, 0, 64, false)));
    }

    public static ObjectNode reviewDecision() {
        var contribution = object(RequirementReview.Contribution.class, "slug", project(),
                "quote", text(4, 280), "purpose", text(1, 300));
        var criterion = object(CriterionReview.class, "source", text(1, 4000),
                "evidence", array(contribution, 0, CriterionReview.MAX_EVIDENCE, false), "gap", text(0, 500));
        var requirement = object(RequirementReview.class, "id", text(18, 18).put("pattern", "^r-[a-f0-9]{16}$"),
                "evidence", array(contribution, 0, RequirementReview.MAX_EVIDENCE, false), "gap", text(0, 500), "criteria", array(criterion, 0, 16, false));
        var hasChildren = MAPPER.createObjectNode();
        hasChildren.putArray("required").add("criteria");
        hasChildren.putObject("properties").putObject("criteria").put("minItems", 1);
        var parentOnly = MAPPER.createObjectNode();
        var parentFields = parentOnly.putObject("properties");
        parentFields.putObject("evidence").put("maxItems", 0);
        parentFields.putObject("gap").put("maxLength", 0);
        var flattened = requirement.putArray("allOf").addObject();
        flattened.set("if", hasChildren); flattened.set("then", parentOnly);
        ObjectNode rejection = object(ReviewDecision.Rejection.class,
                "slug", project(), "reason", enumeration(ReviewDecision.Reason.class),
                "explanation", text(1, ContractRules.MAX_REASON));
        return root("maa-review-decision-v1", object(ReviewDecision.class,
                "schemaVersion", version(), "approved", projects(),
                "rejected", array(rejection, 0, ContractRules.MAX_MODS, false), "allowBackfill", type("boolean"),
                "requirements", array(requirement, 0, 64, false)));
    }

    public static ObjectNode toolResult() {
        ObjectNode issue = object(ToolResult.Issue.class,
                "input", text(0, ContractRules.MAX_REASON), "code", enumeration(ToolResult.IssueCode.class),
                "message", text(1, ContractRules.MAX_REASON));
        return root("maa-tool-result-v1", object(ToolResult.class,
                "schemaVersion", version(), "operationId", text(1, 128).put("pattern", "^[A-Za-z0-9._-]{1,128}$"),
                "tool", enumeration(ToolResult.Tool.class), "status", enumeration(ToolResult.Status.class),
                "message", text(1, ContractRules.MAX_REPLY),
                "applied", array(text(1, ContractRules.MAX_REASON), 0, ContractRules.MAX_MODS, false),
                "issues", array(issue, 0, ContractRules.MAX_MODS, false)));
    }

    private static ObjectNode root(String title, ObjectNode schema) {
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", title);
        return schema;
    }

    private static ObjectNode type(String name) { return MAPPER.createObjectNode().put("type", name); }
    private static ObjectNode text(int min, int max) { return type("string").put("minLength", min).put("maxLength", max); }
    private static ObjectNode integer(long min, long max) { return type("integer").put("minimum", min).put("maximum", max); }
    private static ObjectNode version() { return type("integer").put("const", ContractRules.VERSION); }
    private static ObjectNode project() { return text(1, 128).put("pattern", ContractRules.PROJECT_PATTERN); }
    private static ObjectNode projects() { return array(project(), 0, ContractRules.MAX_MODS, true); }

    private static ObjectNode strings(List<String> values) {
        ObjectNode node = type("string");
        var items = node.putArray("enum");
        values.forEach(items::add);
        return node;
    }

    private static ObjectNode enumeration(Class<? extends Enum<?>> type) {
        return strings(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    private static ObjectNode array(JsonNode item, int min, int max, boolean unique) {
        ObjectNode node = type("array").put("minItems", min).put("maxItems", max);
        node.set("items", item);
        if (unique) node.put("uniqueItems", true);
        return node;
    }

    private static ObjectNode nullable(ObjectNode value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.putArray("anyOf").add(value).add(type("null"));
        return node;
    }

    private static ObjectNode object(Class<?> record, Object... fields) {
        ObjectNode node = type("object").put("additionalProperties", false);
        ObjectNode properties = node.putObject("properties");
        var required = node.putArray("required");
        for (int i = 0; i < fields.length; i += 2) {
            String name = (String) fields[i];
            properties.set(name, (JsonNode) fields[i + 1]);
            required.add(name);
        }
        Set<String> recordFields = Arrays.stream(record.getRecordComponents()).map(component -> component.getName()).collect(Collectors.toSet());
        Set<String> schemaFields = properties.propertyStream().map(entry -> entry.getKey()).collect(Collectors.toSet());
        if (!recordFields.equals(schemaFields)) throw new IllegalStateException("Agent schema 与 Java record 字段不一致: " + record.getSimpleName());
        return node;
    }
}

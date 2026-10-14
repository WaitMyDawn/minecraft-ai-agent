package yagen.waitmydawn.maa.model.agent;

import yagen.waitmydawn.maa.service.ModrinthIdentifiers;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 协议边界的本地校验；不查询上游，也不将模型陈述视为已验证事实。 */
final class ContractRules {
    static final int VERSION = 1;
    static final int MAX_MODS = 500;
    static final int MAX_SEARCH_INTENTS = 19;
    static final int MAX_KEYWORDS = 7;
    static final int MAX_REPLY = 2000;
    static final int MAX_REASON = 500;
    static final String PROJECT_PATTERN = "^(?:[A-Za-z0-9]{1,128}|[A-Za-z0-9_!@$()`.+,\"\\-']{3,64})$";

    private ContractRules() {}

    static final class Violation extends IllegalArgumentException {
        final String field;
        Violation(String field) {
            super("Agent 协议字段无效: " + field);
            this.field = field;
        }
    }

    static void require(boolean condition, String field) {
        if (!condition) throw new Violation(field);
    }

    static void version(int version) { require(version == VERSION, "schemaVersion"); }
    static void buildVersion(int version) { require(version >= VERSION && version <= 3, "schemaVersion"); }

    static <T> T required(T value, String field) {
        require(value != null, field);
        return value;
    }

    static String text(String value, String field, int min, int max) {
        required(value, field);
        require(value.length() >= min && value.length() <= max && value.equals(value.trim())
                && value.chars().noneMatch(c -> Character.isISOControl(c)), field);
        return value;
    }

    static <T> List<T> list(List<T> values, String field, int max) {
        required(values, field);
        require(values.size() <= max && values.stream().noneMatch(Objects::isNull), field);
        return List.copyOf(values);
    }

    static List<String> projects(List<String> values, String field) {
        List<String> result = list(values, field, MAX_MODS);
        require(new HashSet<>(result).size() == result.size(), field + ".duplicates");
        result.forEach(value -> require(ModrinthIdentifiers.isProjectReference(value), field));
        return result;
    }

    static void disjoint(List<String> first, List<String> second, String field) {
        require(first.stream().noneMatch(second::contains), field);
    }
}

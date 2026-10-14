package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalPolicyTest {
    @Test void concurrentReservationsRespectCapsAndReuseKeys() throws Exception {
        var budget = new RetrievalBudget();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 1000; i++) {
                int n = i;
                work.add(executor.submit(() -> { budget.core("c" + n); budget.search("s" + n); budget.version("v" + n); }));
            }
            for (var task : work) task.get();
        }
        assertEquals(8, budget.snapshot().get("addonCores"));
        assertEquals(42, budget.snapshot().get("searchQueries"));
        assertEquals(300, budget.snapshot().get("candidateVersions"));
        var reused = new RetrievalBudget();
        for (int i = 0; i < 1000; i++) assertTrue(reused.version("same"));
        assertEquals(1, reused.snapshot().get("candidateVersions"));
        budget.unknown();
        assertEquals(4, budget.diagnostics().size());
        assertTrue(budget.diagnostics().getLast().contains("不能据此判断"));
    }

    @Test void smallMultiCategoryQuotaDoesNotRoundEveryCategoryUp() {
        var weights = new LinkedHashMap<String, Integer>();
        weights.put("magic", 1); weights.put("adventure", 1); weights.put("utility", 1);
        assertEquals(Map.of("magic", 1, "adventure", 0, "utility", 0), WeightedAllocation.allocate(weights, 1));
        assertEquals(Map.of("magic", 50, "adventure", 50, "utility", 50), WeightedAllocation.allocate(weights, 150));
        weights.put("magic", Integer.MAX_VALUE); weights.put("adventure", Integer.MAX_VALUE);
        assertEquals(150, WeightedAllocation.allocate(weights, 150).values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void namesAloneDoNotProveAddonRelationships() {
        assertEquals(0, AddonEvidence.textStrength("irons-spells-n-spellbooks", "Unrelated spells mod"));
        assertTrue(AddonEvidence.textStrength("irons-spells-n-spellbooks", "Iron's spells and spellbooks addon") > 0);
        var mapper = new ObjectMapper();
        var version = mapper.createObjectNode();
        var deps = version.putArray("dependencies");
        deps.addObject().put("project_id", "other").put("dependency_type", "required");
        assertNull(AddonEvidence.relationship("core", version, Map.of()));
        deps.addObject().put("version_id", "ref").put("dependency_type", "optional");
        assertEquals("OPTIONAL_CORE", AddonEvidence.relationship("core", version, Map.of("ref", "core")));
        deps.addObject().put("project_id", "core").put("dependency_type", "required");
        assertEquals("REQUIRED_CORE", AddonEvidence.relationship("core", version, Map.of("ref", "core")));
        deps.addObject().put("version_id", "conflict").put("dependency_type", "incompatible");
        assertNull(AddonEvidence.relationship("core", version, Map.of("ref", "core", "conflict", "core")));
    }
}

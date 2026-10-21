import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** Standalone fixed-fact CPU probe; no model, network, account or database access. */
public final class CoverageCostProbe {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var graph = new DependencyGraph();
        Map<String, PackAddonPolicy.Project> metadata = new LinkedHashMap<>();
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < 154; i++) {
            String slug = "content-" + i, id = "Project" + i, version = "Version" + i;
            String description = i < 2 ? "A collection of spells, recipes and adventures."
                    : i % 7 == 0 ? "Integration between Content 0 and Content 1, with recipes and items."
                    : "Adds structures and creatures for exploration, equipment and cooking in an adventure world.";
            var v = json.createObjectNode().put("id", version).put("project_id", id);
            var deps = v.putArray("dependencies");
            if (i > 1 && i % 3 == 0) deps.addObject().put("project_id", "Project0").put("dependency_type", "required");
            graph.allSlugs.add(slug); graph.selectedVersions.put(slug, v);
            metadata.put(slug, new PackAddonPolicy.Project(id, slug, 1000L, Set.of("adventure"), "Content " + i, version, description));
            facts.put(slug, description);
        }
        facts.put("content-2", "Overhauls Nether terrain with many new biomes and structures.");
        facts.put("content-3", "Overhauls End terrain with many new biomes and structures.");
        var addon = new BuildRequirement("r-0000000000000001", "Expand both cores", BuildRequirement.Kind.GENERAL,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT,
                List.of(new RequirementCriterion("Core zero addons", BuildRequirement.Kind.ADDONS, List.of(), List.of("content-0"), List.of()),
                        new RequirementCriterion("Core one integrations", BuildRequirement.Kind.PACK_INTEGRATIONS, List.of(), List.of("content-1"), List.of())));
        var generation = new BuildRequirement("r-0000000000000002", "丰富下界和末地生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT,
                List.of(new RequirementCriterion("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                        new RequirementCriterion("丰富末地生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of())));
        int rounds = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            var report = RequirementCoverage.assess(List.of(addon, generation), List.of(), graph, graph.allSlugs,
                    Map.of(), metadata, "NOT_REQUESTED", List.of(), Map.of(), facts);
            long assessed = System.nanoTime();
            if (round == 0 && args.length > 1) java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]), json.writeValueAsString(report));
            for (String fact : facts.values()) DimensionGeneration.evidence(new DimensionGeneration.Goal("nether", "下界", ""), fact);
            long dimension = System.nanoTime();
            for (String slug : graph.allSlugs) PackAddonPolicy.assess(slug, graph.selectedVersions.get(slug), Map.of(),
                    Map.of("content-0", "Project0"), metadata.values(), true);
            long relations = System.nanoTime();
            System.out.printf(Locale.ROOT, "round=%d assessMs=%.3f dimensionMs=%.3f relationsMs=%.3f reportHash=%d%n",
                    round, (assessed-start)/1e6, (dimension-assessed)/1e6, (relations-dimension)/1e6, report.hashCode());
        }
    }
}

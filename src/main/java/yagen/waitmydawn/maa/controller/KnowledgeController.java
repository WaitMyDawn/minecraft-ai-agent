package yagen.waitmydawn.maa.controller;

import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.service.KnowledgeDb;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "*")
public class KnowledgeController {

    private final KnowledgeDb knowledgeDb;

    public KnowledgeController(KnowledgeDb knowledgeDb) {
        this.knowledgeDb = knowledgeDb;
    }

    @GetMapping
    public List<KnowledgeRule> getAllRules() {
        return knowledgeDb.getAllRules();
    }
}
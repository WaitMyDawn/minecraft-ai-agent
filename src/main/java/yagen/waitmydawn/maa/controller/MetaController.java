package yagen.waitmydawn.maa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import yagen.waitmydawn.maa.model.CategoryRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 元数据接口（P3-1）。
 *
 * <p>把后端的权威清单暴露给前端，避免前端再维护一份硬编码副本：
 * 以前类别清单同时存在于 Architect 提示词、ModrinthCacheService 与 index.html 三处。
 */
@RestController
@RequestMapping("/api/meta")
@CrossOrigin(origins = "*")
public class MetaController {

    /** 19 个规范模组类别 + 中文释义（唯一权威源：CategoryRegistry） */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categories", CategoryRegistry.CATEGORIES);
        Map<String, String> gloss = new LinkedHashMap<>();
        for (String c : CategoryRegistry.CATEGORIES) {
            gloss.put(c, CategoryRegistry.glossOf(c));
        }
        body.put("gloss", gloss);
        return ResponseEntity.ok(body);
    }
}

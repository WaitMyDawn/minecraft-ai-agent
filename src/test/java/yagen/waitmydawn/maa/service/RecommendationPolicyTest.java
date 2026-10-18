package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationPolicyTest {
    @Test void explicitNamedOnlyPolicyCanBeReleasedByLaterPermission() {
        assertTrue(RecommendationPolicy.namedOnly("包含核心，不额外推荐"));
        assertTrue(RecommendationPolicy.namedOnly("只添加指定模组，前置正常补充"));
        assertFalse(RecommendationPolicy.namedOnly("不额外推荐，但可以额外推荐"));
        assertTrue(RecommendationPolicy.namedOnly("允许额外推荐；之后不要额外推荐"));
        assertFalse(RecommendationPolicy.namedOnly("丰富生态与现代界面"));
        assertTrue(RecommendationPolicy.namedOnly("只添加named-one、named-two和必要前置。不要其它内容"));
        assertTrue(RecommendationPolicy.namedOnly("只加入named-one"));
        assertFalse(RecommendationPolicy.namedOnly("只添加优化模组和必要前置，不增加内容模组"));
        assertFalse(RecommendationPolicy.namedOnly("只添加提升帧率的模组，不增加内容模组"));
        assertFalse(RecommendationPolicy.namedOnly("只使用NeoForge1.21.1，不使用其它加载器"));
    }
}

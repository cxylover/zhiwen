package com.tongji.cache.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache/caffeine")
public class CaffeineCacheStatsController {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final Cache<String, FeedPageResponse> feedMineCache;
    private final Cache<String, KnowPostDetailResponse> knowPostDetailCache;

    public CaffeineCacheStatsController(
            @Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
            @Qualifier("feedMineCache") Cache<String, FeedPageResponse> feedMineCache,
            @Qualifier("knowPostDetailCache") Cache<String, KnowPostDetailResponse> knowPostDetailCache
    ) {
        this.feedPublicCache = feedPublicCache;
        this.feedMineCache = feedMineCache;
        this.knowPostDetailCache = knowPostDetailCache;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("feedPublicCache", snapshot(feedPublicCache));
        result.put("feedMineCache", snapshot(feedMineCache));
        result.put("knowPostDetailCache", snapshot(knowPostDetailCache));
        return result;
    }

    private Map<String, Object> snapshot(Cache<?, ?> cache) {
        CacheStats stats = cache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estimatedSize", cache.estimatedSize());
        result.put("requestCount", stats.requestCount());
        result.put("hitCount", stats.hitCount());
        result.put("missCount", stats.missCount());
        result.put("hitRate", stats.hitRate());
        result.put("missRate", stats.missRate());
        result.put("evictionCount", stats.evictionCount());
        return result;
    }
}

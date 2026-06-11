package com.tongji.knowpost.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.event.CounterEvent;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Invalidates local feed cache entries affected by counter changes.
 */
@Component
public class FeedCacheInvalidationListener {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final StringRedisTemplate redis;

    public FeedCacheInvalidationListener(
            @Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
            StringRedisTemplate redis
    ) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
    }

    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!"knowpost".equals(event.getEntityType())) {
            return;
        }

        String metric = event.getMetric();
        if (!"like".equals(metric) && !"fav".equals(metric)) {
            return;
        }

        String eid = event.getEntityId();
        long hourSlot = System.currentTimeMillis() / 3600000L;
        Set<String> keys = new LinkedHashSet<>();

        Set<String> current = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
        if (current != null) {
            keys.addAll(current);
        }

        Set<String> previous = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
        if (previous != null) {
            keys.addAll(previous);
        }

        for (String key : keys) {
            feedPublicCache.invalidate(key);
        }
    }
}

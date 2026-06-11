package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import com.tongji.counter.schema.UserCounterKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates counter events and periodically folds them into SDS counters.
 */
@Service
public class CounterAggregationConsumer {

    private static final String USER_AGG_PREFIX = "uagg:" + CounterSchema.SCHEMA_ID + ":";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> entityIncrScript;
    private final DefaultRedisScript<Long> userIncrScript;
    private final DefaultRedisScript<Long> decrScript;

    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;

        this.entityIncrScript = new DefaultRedisScript<>();
        this.entityIncrScript.setResultType(Long.class);
        this.entityIncrScript.setScriptText(INCR_ENTITY_FIELD_LUA);

        this.userIncrScript = new DefaultRedisScript<>();
        this.userIncrScript.setResultType(Long.class);
        this.userIncrScript.setScriptText(INCR_USER_FIELD_LUA);

        this.decrScript = new DefaultRedisScript<>();
        this.decrScript.setResultType(Long.class);
        this.decrScript.setScriptText(DECR_FIELD_LUA);
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg")
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        CounterEvent evt = objectMapper.readValue(message, CounterEvent.class);
        try {
            redis.opsForHash().increment(
                    CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId()),
                    String.valueOf(evt.getIdx()),
                    evt.getDelta()
            );
            aggregateAuthorCounter(evt);
            ack.acknowledge();
        } catch (Exception ex) {
            // Do not ack. Kafka will retry this event.
        }
    }

    private void aggregateAuthorCounter(CounterEvent evt) {
        Long authorId = evt.getAuthorId();
        if (authorId == null || authorId <= 0 || !"knowpost".equals(evt.getEntityType())) {
            return;
        }

        String field = switch (evt.getMetric()) {
            case "like" -> "4";
            case "fav" -> "5";
            default -> null;
        };
        if (field == null) {
            return;
        }

        redis.opsForHash().increment(userAggKey(authorId), field, evt.getDelta());
    }

    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        flushEntityCounters();
        flushUserCounters();
    }

    private void flushEntityCounters() {
        Set<String> keys = redis.keys("agg:" + CounterSchema.SCHEMA_ID + ":*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
            if (entries.isEmpty()) {
                continue;
            }

            String[] parts = aggKey.split(":", 4);
            if (parts.length < 4) {
                continue;
            }

            String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);
            foldHashIntoSds(aggKey, cntKey, entries, entityIncrScript);
        }
    }

    private void flushUserCounters() {
        Set<String> keys = redis.keys(USER_AGG_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);
            if (entries.isEmpty()) {
                continue;
            }

            String authorId = aggKey.substring(USER_AGG_PREFIX.length());
            String cntKey = UserCounterKeys.sdsKey(Long.parseLong(authorId));
            foldHashIntoSds(aggKey, cntKey, entries, userIncrScript);
        }
    }

    private void foldHashIntoSds(
            String aggKey,
            String cntKey,
            Map<Object, Object> entries,
            DefaultRedisScript<Long> incrScript
    ) {
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = String.valueOf(entry.getKey());
            long delta;
            int idx;
            try {
                delta = Long.parseLong(String.valueOf(entry.getValue()));
                idx = Integer.parseInt(field);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (delta == 0) {
                continue;
            }

            try {
                redis.execute(incrScript, List.of(cntKey),
                        String.valueOf(CounterSchema.SCHEMA_LEN),
                        String.valueOf(CounterSchema.FIELD_SIZE),
                        String.valueOf(idx),
                        String.valueOf(delta));
                redis.execute(decrScript, List.of(aggKey), field, String.valueOf(delta));
            } catch (Exception ex) {
                // Leave the hash field for the next scheduled flush.
            }
        }

        Long size = redis.opsForHash().size(aggKey);
        if (size != null && size == 0L) {
            redis.delete(aggKey);
        }
    }

    private static String userAggKey(long authorId) {
        return USER_AGG_PREFIX + authorId;
    }

    private static final String INCR_ENTITY_FIELD_LUA = """
            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = idx * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static final String INCR_USER_FIELD_LUA = """
            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])

            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end

            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end

            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = (idx - 1) * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static final String DECR_FIELD_LUA = """
            local key = KEYS[1]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local v = redis.call('HINCRBY', key, field, -delta)
            if v == 0 then
                redis.call('HDEL', key, field)
            end
            return v
            """;
}

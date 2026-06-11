package com.tongji.counter.event;

import lombok.Data;

/**
 * Counter delta event.
 */
@Data
public class CounterEvent {
    private String entityType;
    private String entityId;
    private String metric;
    private int idx;
    private long userId;
    private int delta;
    private Long authorId;

    public CounterEvent() {
    }

    public CounterEvent(String entityType, String entityId, String metric, int idx, long userId, int delta, Long authorId) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.metric = metric;
        this.idx = idx;
        this.userId = userId;
        this.delta = delta;
        this.authorId = authorId;
    }

    public static CounterEvent of(String entityType, String entityId, String metric, int idx, long userId, int delta, Long authorId) {
        return new CounterEvent(entityType, entityId, metric, idx, userId, delta, authorId);
    }
}

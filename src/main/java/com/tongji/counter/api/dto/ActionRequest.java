package com.tongji.counter.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for like/favorite actions.
 */
@Data
public class ActionRequest {
    @NotBlank
    private String entityType;

    @NotBlank
    private String entityId;

    /**
     * Optional owner of the target entity. When present, author-level counters can
     * be updated asynchronously without querying MySQL on the request thread.
     */
    private Long authorId;
}

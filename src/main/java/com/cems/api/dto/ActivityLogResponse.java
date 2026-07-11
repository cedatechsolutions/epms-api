package com.cems.api.dto;

import com.cems.api.entity.ActivityLog;

import java.time.Instant;

public class ActivityLogResponse {

    private String id;
    private String userId;
    private String userLabel;
    private String action;
    private String entityType;
    private String entityId;
    private String metadata;
    private String ipAddress;
    private Instant createdAt;

    public static ActivityLogResponse fromEntity(ActivityLog log, String userLabel) {
        ActivityLogResponse response = new ActivityLogResponse();
        response.id = log.getId();
        response.userId = log.getUserId();
        response.userLabel = userLabel;
        response.action = log.getAction();
        response.entityType = log.getEntityType();
        response.entityId = log.getEntityId();
        response.metadata = log.getMetadata();
        response.ipAddress = log.getIpAddress();
        response.createdAt = log.getCreatedAt();
        return response;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserLabel() {
        return userLabel;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

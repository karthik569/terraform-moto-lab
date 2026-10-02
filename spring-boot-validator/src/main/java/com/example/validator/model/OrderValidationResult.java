package com.example.validator.model;

import java.time.Instant;
import java.util.Map;

public record OrderValidationResult(
    String orderId,
    boolean valid,
    String pipelineStatus,
    boolean queuedInSqs,
    boolean persistedInDynamoDb,
    boolean snsEventDispatched,
    Map<String, Object> dynamoDbRecord,
    String message,
    Instant validatedAt
) {}

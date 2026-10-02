package com.example.validator.service;

import com.example.validator.model.OrderRequest;
import com.example.validator.model.OrderValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.*;

@Service
public class OrderPipelineValidatorService {

    private static final Logger log = LoggerFactory.getLogger(OrderPipelineValidatorService.class);

    private final SqsClient sqsClient;
    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;

    @Value("${pipeline.queue-url}")
    private String queueUrl;

    @Value("${pipeline.dlq-url}")
    private String dlqUrl;

    @Value("${pipeline.table-name}")
    private String tableName;

    @Value("${pipeline.topic-arn}")
    private String topicArn;

    public OrderPipelineValidatorService(
        SqsClient sqsClient,
        DynamoDbClient dynamoDbClient,
        SnsClient snsClient,
        ObjectMapper objectMapper
    ) {
        this.sqsClient = sqsClient;
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Verifies infrastructure components are accessible and properly configured.
     */
    public Map<String, Object> checkInfrastructureHealth() {
        Map<String, Object> report = new LinkedHashMap<>();

        // Check SQS
        try {
            GetQueueAttributesResponse attrs = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.REDRIVE_POLICY, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                    .build()
            );
            report.put("sqsMainQueue", "HEALTHY");
            report.put("sqsRedrivePolicy", attrs.attributes().get(QueueAttributeName.REDRIVE_POLICY));
        } catch (Exception e) {
            report.put("sqsMainQueue", "ERROR: " + e.getMessage());
        }

        // Check DynamoDB
        try {
            DescribeTableResponse ddbResp = dynamoDbClient.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()
            );
            report.put("dynamoDbTable", ddbResp.table().tableStatusAsString());
        } catch (Exception e) {
            report.put("dynamoDbTable", "ERROR: " + e.getMessage());
        }

        // Check SNS
        try {
            snsClient.getTopicAttributes(builder -> builder.topicArn(topicArn));
            report.put("snsTopic", "HEALTHY");
        } catch (Exception e) {
            report.put("snsTopic", "ERROR: " + e.getMessage());
        }

        return report;
    }

    /**
     * Simulates Producer Lambda: Enqueues order payload into SQS queue.
     */
    public String enqueueOrder(OrderRequest request, String orderId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("order_id", orderId);
            payload.put("customer_id", request.customerId());
            payload.put("order_type", request.orderType() != null ? request.orderType() : "Standard");
            payload.put("items", request.items());
            payload.put("total_amount", request.totalAmount());
            payload.put("status", "QUEUED");
            payload.put("created_at", Instant.now().toString());

            String messageBody = objectMapper.writeValueAsString(payload);

            SendMessageResponse response = sqsClient.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageAttributes(Map.of(
                        "OrderType", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(request.orderType() != null ? request.orderType() : "Standard")
                            .build()
                    ))
                    .build()
            );

            log.info("Order {} enqueued to SQS. Message ID: {}", orderId, response.messageId());
            return response.messageId();
        } catch (Exception e) {
            log.error("Failed to enqueue order to SQS: {}", e.getMessage(), e);
            throw new RuntimeException("SQS enqueue error: " + e.getMessage(), e);
        }
    }

    /**
     * Simulates Consumer Lambda: Receives message from SQS, updates DynamoDB, and dispatches SNS notification.
     */
    public boolean processNextQueueOrder() {
        try {
            ReceiveMessageResponse receiveResp = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(2)
                    .build()
            );

            if (receiveResp.messages().isEmpty()) {
                log.warn("No message available in SQS queue for processing");
                return false;
            }

            Message message = receiveResp.messages().get(0);
            Map<String, Object> orderData = objectMapper.readValue(message.body(), Map.class);
            String orderId = (String) orderData.get("order_id");
            String processedAt = Instant.now().toString();

            // 1. Put item into DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("order_id", AttributeValue.builder().s(orderId).build());
            item.put("customer_id", AttributeValue.builder().s(String.valueOf(orderData.get("customer_id"))).build());
            item.put("status", AttributeValue.builder().s("COMPLETED").build());
            item.put("processed_at", AttributeValue.builder().s(processedAt).build());
            item.put("total_amount", AttributeValue.builder().n(String.valueOf(orderData.get("total_amount"))).build());

            dynamoDbClient.putItem(
                PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build()
            );
            log.info("Order {} written to DynamoDB table {}", orderId, tableName);

            // 2. Publish event to SNS
            Map<String, Object> snsEvent = Map.of(
                "event", "OrderCompleted",
                "order_id", orderId,
                "status", "COMPLETED",
                "processed_at", processedAt
            );

            snsClient.publish(
                PublishRequest.builder()
                    .topicArn(topicArn)
                    .subject("Order Completed: " + orderId)
                    .message(objectMapper.writeValueAsString(snsEvent))
                    .build()
            );
            log.info("Dispatched OrderCompleted notification for {} to SNS topic {}", orderId, topicArn);

            // 3. Delete from SQS
            sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build()
            );

            return true;
        } catch (Exception e) {
            log.error("Consumer processing failed: {}", e.getMessage(), e);
            throw new RuntimeException("Consumer processing error: " + e.getMessage(), e);
        }
    }

    /**
     * Queries DynamoDB for an order by primary key.
     */
    public Map<String, Object> fetchOrderRecord(String orderId) {
        GetItemResponse response = dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("order_id", AttributeValue.builder().s(orderId).build()))
                .build()
        );

        if (!response.hasItem() || response.item().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        response.item().forEach((k, v) -> {
            if (v.s() != null) result.put(k, v.s());
            else if (v.n() != null) result.put(k, Double.parseDouble(v.n()));
            else result.put(k, v.toString());
        });
        return result;
    }

    /**
     * Executes the full pipeline validation lifecycle for an order request.
     */
    public OrderValidationResult validateFullPipeline(OrderRequest request) {
        String orderId = (request.orderId() != null && !request.orderId().isBlank())
            ? request.orderId()
            : "ord-" + UUID.randomUUID().toString().substring(0, 8);

        Instant start = Instant.now();

        // 1. Enqueue to SQS (Producer)
        String sqsMessageId = enqueueOrder(request, orderId);
        boolean queued = sqsMessageId != null;

        // 2. Consume from SQS, update DynamoDB, dispatch SNS (Consumer)
        boolean processed = processNextQueueOrder();

        // 3. Fetch from DynamoDB and verify
        Map<String, Object> ddbRecord = fetchOrderRecord(orderId);
        boolean persisted = !ddbRecord.isEmpty() && "COMPLETED".equals(ddbRecord.get("status"));

        boolean allValid = queued && processed && persisted;

        return new OrderValidationResult(
            orderId,
            allValid,
            persisted ? "COMPLETED" : "FAILED",
            queued,
            persisted,
            processed,
            ddbRecord,
            allValid ? "Pipeline validated successfully against Moto emulator" : "Validation failed during execution",
            start
        );
    }
}

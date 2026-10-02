package com.example.validator;

import com.example.validator.model.OrderItem;
import com.example.validator.model.OrderRequest;
import com.example.validator.model.OrderValidationResult;
import com.example.validator.service.OrderPipelineValidatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderPipelineIntegrationTest {

    @Autowired
    private OrderPipelineValidatorService validatorService;

    @Test
    @DisplayName("Verify simulated AWS Infrastructure is Healthy")
    void testInfrastructureHealth() {
        Map<String, Object> health = validatorService.checkInfrastructureHealth();

        assertThat(health)
            .containsEntry("sqsMainQueue", "HEALTHY")
            .containsEntry("dynamoDbTable", "ACTIVE")
            .containsEntry("snsTopic", "HEALTHY");

        assertThat(health.get("sqsRedrivePolicy").toString())
            .contains("deadLetterTargetArn")
            .contains("maxReceiveCount")
            .contains("3");
    }

    @Test
    @DisplayName("Verify Complete End-to-End Pipeline Execution: SQS -> DynamoDB -> SNS")
    void testProducerConsumerPipelineEndToEnd() {
        String testOrderId = "spring-ord-" + UUID.randomUUID().toString().substring(0, 8);

        OrderRequest request = new OrderRequest(
            testOrderId,
            "cust-spring-test-01",
            "Priority",
            List.of(
                new OrderItem("LAPTOP-01", "Developer Laptop 32GB", 1, 1499.00),
                new OrderItem("MOUSE-02", "Ergonomic Mouse", 2, 49.50)
            ),
            1598.00
        );

        OrderValidationResult result = validatorService.validateFullPipeline(request);

        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(testOrderId);
        assertThat(result.valid()).isTrue();
        assertThat(result.queuedInSqs()).isTrue();
        assertThat(result.persistedInDynamoDb()).isTrue();
        assertThat(result.snsEventDispatched()).isTrue();
        assertThat(result.pipelineStatus()).isEqualTo("COMPLETED");

        Map<String, Object> ddb = result.dynamoDbRecord();
        assertThat(ddb).isNotEmpty();
        assertThat(ddb.get("order_id")).isEqualTo(testOrderId);
        assertThat(ddb.get("customer_id")).isEqualTo("cust-spring-test-01");
        assertThat(ddb.get("status")).isEqualTo("COMPLETED");
        assertThat(ddb.get("processed_at")).isNotNull();
        assertThat(Double.parseDouble(ddb.get("total_amount").toString())).isEqualTo(1598.00);
    }
}

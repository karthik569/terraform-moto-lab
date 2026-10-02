package com.example.validator.controller;

import com.example.validator.model.OrderRequest;
import com.example.validator.model.OrderValidationResult;
import com.example.validator.service.OrderPipelineValidatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/validate")
@Tag(name = "Pipeline Validator", description = "Endpoints for verifying and exercising the simulated AWS serverless order architecture")
public class ValidationController {

    private final OrderPipelineValidatorService validatorService;

    public ValidationController(OrderPipelineValidatorService validatorService) {
        this.validatorService = validatorService;
    }

    @GetMapping("/health")
    @Operation(
        summary = "Check simulated AWS infrastructure health",
        description = "Validates active connection to Moto, verifies SQS queue and DLQ redrive policy, confirms DynamoDB table status, and checks SNS topic."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Health status report returned successfully")
    })
    public ResponseEntity<Map<String, Object>> checkHealth() {
        return ResponseEntity.ok(validatorService.checkInfrastructureHealth());
    }

    @PostMapping("/pipeline")
    @Operation(
        summary = "Execute full end-to-end pipeline validation",
        description = "Submits an order, verifies SQS enqueuing (producer), simulates worker consumption (consumer), verifies DynamoDB record insertion, and ensures SNS notification delivery."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Pipeline test executed and status returned",
            content = @Content(schema = @Schema(implementation = OrderValidationResult.class))
        ),
        @ApiResponse(responseCode = "400", description = "Invalid order payload format")
    })
    public ResponseEntity<OrderValidationResult> validatePipeline(
        @Valid @RequestBody OrderRequest request
    ) {
        OrderValidationResult result = validatorService.validateFullPipeline(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/order/{orderId}")
    @Operation(
        summary = "Query order record from DynamoDB",
        description = "Retrieves an existing order record directly from the DynamoDB table using its primary key."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order record found"),
        @ApiResponse(responseCode = "404", description = "Order not found in DynamoDB")
    })
    public ResponseEntity<?> getOrder(
        @Parameter(description = "Primary key of the order to retrieve", example = "ord-c3d9d62e")
        @PathVariable String orderId
    ) {
        Map<String, Object> record = validatorService.fetchOrderRecord(orderId);
        if (record.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }
}

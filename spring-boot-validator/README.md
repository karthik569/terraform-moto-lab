# Spring Boot AWS Pipeline Validator

A Spring Boot 3.3 (Java 21) test application designed to validate and interact with the serverless pipeline (SQS, DynamoDB, SNS) simulated on the local Moto server.

---

## 1. Project Structure

```
spring-boot-validator/
├── pom.xml                                      # Spring Boot 3.3.4 + AWS SDK for Java v2 BOM
├── src/
│   ├── main/
│   │   ├── java/com/example/validator/
│   │   │   ├── ValidatorApplication.java        # Spring Boot main entrypoint
│   │   │   ├── config/
│   │   │   │   └── AwsConfig.java               # Configures SqsClient, DynamoDbClient, SnsClient
│   │   │   ├── model/
│   │   │   │   ├── OrderItem.java               # Order item model (sku, name, qty, price)
│   │   │   │   ├── OrderRequest.java            # Inbound order payload
│   │   │   │   └── OrderValidationResult.java   # Pipeline validation report
│   │   │   ├── service/
│   │   │   │   └── OrderPipelineValidatorService.java # SQS enqueue, consumer simulation & DDB query
│   │   │   └── controller/
│   │   │       └── ValidationController.java    # REST API (/api/validate/health, /pipeline, /order)
│   │   └── resources/
│   │       └── application.yml                  # Moto endpoints & resource parameters
│   └── test/
│       ├── java/com/example/validator/
│       │   └── OrderPipelineIntegrationTest.java # Full E2E Spring Boot integration tests
│       └── resources/
│           └── mockito-extensions/
│               └── org.mockito.plugins.MockMaker # Configured for PRoot (mock-maker-subclass)
```

---

## 2. Key Features

- **AWS SDK for Java v2**: Uses lightweight `UrlConnectionHttpClient` optimized for ARM64 PRoot.
- **Swagger / OpenAPI 3.0 UI**:
  - Interactive UI: `http://localhost:8080/swagger-ui/index.html`
  - OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- **REST Endpoints**:
  - `GET /api/validate/health`: Checks Moto SQS, DynamoDB, and SNS availability & DLQ redrive policy.
  - `POST /api/validate/pipeline`: Submits an order, exercises the queue/consume loop, and returns validation status.
  - `GET /api/validate/order/{orderId}`: Retrieves persisted order records from DynamoDB.
- **Automated Integration Testing**:
  - `OrderPipelineIntegrationTest` executes inside Spring Boot test context asserting real SQS enqueuing, DynamoDB records, and SNS notifications against Moto.

---

## 3. Running Tests

```bash
mvn test -Dtest=OrderPipelineIntegrationTest
```

---

## 4. Running the Spring Boot Application

To launch the validator service:
```bash
mvn spring-boot:run
```

Once running on port `8080`, test with curl:

```bash
# 1. Health check
curl -s http://localhost:8080/api/validate/health | jq .

# 2. Trigger pipeline validation
curl -s -X POST http://localhost:8080/api/validate/pipeline \
  -H "Content-Type: application/json" \
  -d '{
    "order_id": "curl-ord-001",
    "customer_id": "cust-demo",
    "order_type": "Express",
    "items": [{"sku": "KEY-01", "name": "Keychron K2", "qty": 1, "price": 89.99}],
    "total_amount": 89.99
  }' | jq .

# 3. Query DynamoDB record via validator
curl -s http://localhost:8080/api/validate/order/curl-ord-001 | jq .
```

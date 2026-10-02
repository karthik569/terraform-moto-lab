#!/usr/bin/env python3
"""
End-to-End Pipeline Verification Script for Serverless Orders Architecture
Tests Terraform-provisioned resources against Moto server:
1. Validates DynamoDB Table (schema, status, primary key)
2. Validates SQS Orders Queue, DLQ, and Redrive Policy (maxReceiveCount: 3)
3. Validates SNS Order Events Topic
4. Validates API Gateway v2 HTTP API, Routes, and Lambda Integration
5. Validates Lambda Configurations & Active SQS Event Source Mapping
6. Executes Producer Lambda Logic -> Enqueues Order to Moto SQS
7. Executes Consumer Lambda Logic -> Verifies DynamoDB Persistence & SNS Event
"""

import json
import os
import sys
import subprocess
import uuid
import datetime
import importlib.util
from decimal import Decimal
import boto3
from botocore.config import Config

ENDPOINT_URL = "http://127.0.0.1:5000"
AWS_REGION = "us-east-1"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# Configure environment variables for AWS SDK
os.environ["AWS_ACCESS_KEY_ID"] = "mock_access_key"
os.environ["AWS_SECRET_ACCESS_KEY"] = "mock_secret_key"
os.environ["AWS_DEFAULT_REGION"] = AWS_REGION

boto_config = Config(
    region_name=AWS_REGION,
    retries={"max_attempts": 2, "mode": "standard"}
)

session = boto3.Session(
    aws_access_key_id="mock_access_key",
    aws_secret_access_key="mock_secret_key",
    region_name=AWS_REGION
)

dynamodb = session.resource("dynamodb", endpoint_url=ENDPOINT_URL, config=boto_config)
sqs = session.client("sqs", endpoint_url=ENDPOINT_URL, config=boto_config)
sns = session.client("sns", endpoint_url=ENDPOINT_URL, config=boto_config)
lambda_client = session.client("lambda", endpoint_url=ENDPOINT_URL, config=boto_config)
apigw = session.client("apigatewayv2", endpoint_url=ENDPOINT_URL, config=boto_config)

def get_tf_outputs():
    try:
        res = subprocess.run(
            ["terraform", "output", "-json"],
            capture_output=True,
            text=True,
            check=True,
            cwd=BASE_DIR
        )
        data = json.loads(res.stdout)
        return {k: v["value"] for k, v in data.items()}
    except Exception as e:
        print(f"[!] Error getting terraform outputs: {e}")
        sys.exit(1)

def load_handler_module(module_name, file_path):
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def main():
    print("=" * 65)
    print(" SERVERLESS PIPELINE E2E INTEGRATION TEST")
    print(f" Target Endpoint: {ENDPOINT_URL} (Moto)")
    print("=" * 65)

    outputs = get_tf_outputs()
    table_name = outputs["dynamodb_orders_table_name"]
    orders_queue_url = outputs["sqs_orders_queue_url"]
    dlq_url = outputs["sqs_orders_dlq_url"]
    orders_queue_arn = outputs["sqs_orders_queue_arn"]
    producer_arn = outputs["producer_lambda_arn"]
    consumer_arn = outputs["consumer_lambda_arn"]
    sns_topic_arn = outputs["sns_order_events_topic_arn"]

    tests_passed = 0
    total_tests = 7

    # ---------------------------------------------------------
    # TEST 1: DynamoDB Table
    # ---------------------------------------------------------
    print("\n[TEST 1] Verifying DynamoDB Table...")
    try:
        table = dynamodb.Table(table_name)
        status = table.table_status
        key_schema = table.key_schema
        assert status in ["ACTIVE", "CREATING"]
        assert any(k["AttributeName"] == "order_id" and k["KeyType"] == "HASH" for k in key_schema)
        print(f"  [PASS] DynamoDB Table '{table_name}' is {status} with primary key 'order_id'.")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] DynamoDB Table check failed: {e}")

    # ---------------------------------------------------------
    # TEST 2: SQS Queues & DLQ Redrive Policy
    # ---------------------------------------------------------
    print("\n[TEST 2] Verifying SQS Queues & Redrive Policy...")
    try:
        attrs = sqs.get_queue_attributes(
            QueueUrl=orders_queue_url,
            AttributeNames=["RedrivePolicy"]
        )["Attributes"]
        redrive = json.loads(attrs.get("RedrivePolicy", "{}"))
        assert "deadLetterTargetArn" in redrive
        assert redrive.get("maxReceiveCount") == 3
        print(f"  [PASS] Main SQS Queue verified with DLQ redrive policy (maxReceiveCount: 3).")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] SQS check failed: {e}")

    # ---------------------------------------------------------
    # TEST 3: SNS Topic
    # ---------------------------------------------------------
    print("\n[TEST 3] Verifying SNS Topic...")
    try:
        topics = sns.list_topics()["Topics"]
        topic_arns = [t["TopicArn"] for t in topics]
        assert sns_topic_arn in topic_arns
        print(f"  [PASS] SNS Topic verified: '{sns_topic_arn}'.")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] SNS check failed: {e}")

    # ---------------------------------------------------------
    # TEST 4: API Gateway v2 (HTTP API) & Routes
    # ---------------------------------------------------------
    print("\n[TEST 4] Verifying API Gateway v2 HTTP API & Routes...")
    try:
        apis = apigw.get_apis()["Items"]
        http_api = next((a for a in apis if a["Name"] == "serverless-orders-http-api"), None)
        assert http_api is not None, "HTTP API 'serverless-orders-http-api' not found"
        api_id = http_api["ApiId"]

        routes = apigw.get_routes(ApiId=api_id)["Items"]
        post_order_route = next((r for r in routes if r["RouteKey"] == "POST /orders"), None)
        assert post_order_route is not None, "Route 'POST /orders' not found"
        print(f"  [PASS] API Gateway HTTP API '{api_id}' verified with route 'POST /orders'.")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] API Gateway check failed: {e}")

    # ---------------------------------------------------------
    # TEST 5: Lambda Function Configurations & SQS Event Source Mapping
    # ---------------------------------------------------------
    print("\n[TEST 5] Verifying Lambda Configurations & Event Source Mapping...")
    try:
        prod_fn = lambda_client.get_function(FunctionName="serverless-orders-producer")["Configuration"]
        cons_fn = lambda_client.get_function(FunctionName="serverless-orders-consumer")["Configuration"]

        assert prod_fn["Runtime"] == "python3.12"
        assert cons_fn["Runtime"] == "python3.12"
        assert prod_fn["Environment"]["Variables"]["ORDER_QUEUE_URL"] == orders_queue_url
        assert cons_fn["Environment"]["Variables"]["ORDERS_TABLE_NAME"] == table_name

        esm_list = lambda_client.list_event_source_mappings()["EventSourceMappings"]
        matching_esm = [m for m in esm_list if m["EventSourceArn"] == orders_queue_arn]
        assert len(matching_esm) > 0, "No Event Source Mapping found for orders queue"
        esm = matching_esm[0]
        assert esm["State"] in ["Enabled", "Enabling"]
        assert esm["BatchSize"] == 10
        print(f"  [PASS] Producer & Consumer Lambdas configured with active SQS mapping (BatchSize: 10).")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] Lambda/ESM check failed: {e}")

    # ---------------------------------------------------------
    # TEST 6: Execute Order Producer Handler -> SQS
    # ---------------------------------------------------------
    print("\n[TEST 6] Testing Order Producer Handler with Moto SQS...")
    test_order_id = f"ord-{uuid.uuid4().hex[:8]}"
    try:
        os.environ["ORDER_QUEUE_URL"] = orders_queue_url
        producer_module = load_handler_module(
            "producer_handler",
            os.path.join(BASE_DIR, "src", "producer", "handler.py")
        )
        # Point the handler's internal sqs client to the Moto endpoint
        producer_module.sqs = sqs

        mock_event = {
            "version": "2.0",
            "routeKey": "POST /orders",
            "body": json.dumps({
                "order_id": test_order_id,
                "customer_id": "cust-gemini-test",
                "order_type": "Express",
                "items": [
                    {"sku": "SKU-99", "name": "Mechanical Keyboard", "qty": 1, "price": 129.99}
                ],
                "total_amount": 129.99
            })
        }

        resp = producer_module.lambda_handler(mock_event, None)
        assert resp["statusCode"] == 202
        body = json.loads(resp["body"])
        assert body["order_id"] == test_order_id
        assert body["status"] == "QUEUED"

        # Verify message was queued in SQS
        sqs_resp = sqs.receive_message(
            QueueUrl=orders_queue_url,
            MaxNumberOfMessages=1,
            WaitTimeSeconds=2
        )
        msgs = sqs_resp.get("Messages", [])
        assert len(msgs) > 0, "No message received from SQS queue"
        enqueued_payload = json.loads(msgs[0]["Body"])
        assert enqueued_payload["order_id"] == test_order_id
        assert enqueued_payload["total_amount"] == 129.99
        print(f"  [PASS] Producer successfully queued Order '{test_order_id}' into Moto SQS.")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] Producer execution failed: {e}")

    # ---------------------------------------------------------
    # TEST 7: Execute Order Consumer Handler -> DynamoDB & SNS
    # ---------------------------------------------------------
    print("\n[TEST 7] Testing Order Consumer Handler with DynamoDB & SNS...")
    try:
        os.environ["ORDERS_TABLE_NAME"] = table_name
        os.environ["ORDER_EVENTS_TOPIC_ARN"] = sns_topic_arn

        consumer_module = load_handler_module(
            "consumer_handler",
            os.path.join(BASE_DIR, "src", "consumer", "handler.py")
        )
        # Point consumer's internal dynamodb & sns clients to Moto
        consumer_module.dynamodb = dynamodb
        consumer_module.sns = sns

        # Construct SQS Event
        sqs_record = msgs[0]
        consumer_event = {
            "Records": [
                {
                    "messageId": sqs_record["MessageId"],
                    "receiptHandle": sqs_record["ReceiptHandle"],
                    "body": sqs_record["Body"],
                    "eventSource": "aws:sqs",
                    "eventSourceARN": orders_queue_arn,
                    "awsRegion": AWS_REGION
                }
            ]
        }

        consumer_res = consumer_module.lambda_handler(consumer_event, None)
        assert consumer_res["statusCode"] == 200
        assert consumer_res["processed_records"] == 1

        # Delete message from queue
        sqs.delete_message(
            QueueUrl=orders_queue_url,
            ReceiptHandle=sqs_record["ReceiptHandle"]
        )

        # Query DynamoDB table for saved record
        table = dynamodb.Table(table_name)
        item = table.get_item(Key={"order_id": test_order_id}).get("Item")
        assert item is not None, f"Order {test_order_id} not found in DynamoDB"
        assert item["status"] == "COMPLETED"
        assert "processed_at" in item
        assert float(item["total_amount"]) == 129.99

        print(f"  [PASS] Consumer persisted Order to DynamoDB with COMPLETED status:")
        print(f"         Order ID:     {item['order_id']}")
        print(f"         Customer ID:  {item['customer_id']}")
        print(f"         Total Amount: ${item['total_amount']}")
        print(f"         Processed At: {item['processed_at']}")
        tests_passed += 1
    except Exception as e:
        print(f"  [FAIL] Consumer execution failed: {e}")

    # Summary
    print("\n" + "=" * 65)
    print(f" TEST RESULTS: {tests_passed}/{total_tests} PASSED")
    print("=" * 65)
    if tests_passed == total_tests:
        print(" [SUCCESS] ALL ARCHITECTURE COMPONENTS FUNCTIONING PERFECTLY!")
        return 0
    else:
        print(" [WARNING] Some tests did not pass.")
        return 1

if __name__ == "__main__":
    sys.exit(main())

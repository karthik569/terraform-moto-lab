import json
import os
import uuid
import datetime
import boto3

sqs = boto3.client("sqs")
QUEUE_URL = os.environ.get("ORDER_QUEUE_URL")

def lambda_handler(event, context):
    try:
        # Parse request body from API Gateway v2 event
        body_raw = event.get("body", "{}")
        if event.get("isBase64Encoded", False):
            import base64
            body_raw = base64.b64decode(body_raw).decode("utf-8")
        
        body = json.loads(body_raw) if isinstance(body_raw, str) else body_raw
        
        # Validate order payload
        customer_id = body.get("customer_id")
        items = body.get("items", [])
        total_amount = body.get("total_amount", 0.0)

        if not customer_id or not items:
            return {
                "statusCode": 400,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({
                    "error": "Bad Request",
                    "message": "'customer_id' and 'items' are required"
                })
            }

        order_id = body.get("order_id", str(uuid.uuid4()))
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()

        order_payload = {
            "order_id": order_id,
            "customer_id": customer_id,
            "items": items,
            "total_amount": float(total_amount),
            "status": "QUEUED",
            "created_at": timestamp
        }

        # Enqueue order to SQS
        response = sqs.send_message(
            QueueUrl=QUEUE_URL,
            MessageBody=json.dumps(order_payload),
            MessageAttributes={
                "OrderType": {
                    "DataType": "String",
                    "StringValue": body.get("order_type", "Standard")
                }
            }
        )

        return {
            "statusCode": 202,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({
                "message": "Order successfully accepted and queued",
                "order_id": order_id,
                "status": "QUEUED",
                "sqs_message_id": response.get("MessageId"),
                "timestamp": timestamp
            })
        }

    except json.JSONDecodeError:
        return {
            "statusCode": 400,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "Invalid JSON payload in request body"})
        }
    except Exception as e:
        return {
            "statusCode": 500,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "Internal Server Error", "details": str(e)})
        }

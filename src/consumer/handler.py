import json
import os
import datetime
from decimal import Decimal
import boto3

dynamodb = boto3.resource("dynamodb")
sns = boto3.client("sns")

TABLE_NAME = os.environ.get("ORDERS_TABLE_NAME")
TOPIC_ARN = os.environ.get("ORDER_EVENTS_TOPIC_ARN")

def lambda_handler(event, context):
    table = dynamodb.Table(TABLE_NAME)
    processed_count = 0
    records = event.get("Records", [])

    for record in records:
        try:
            body_str = record.get("body", "{}")
            order = json.loads(body_str, parse_float=Decimal)
            order_id = order.get("order_id")

            if not order_id:
                print(f"Skipping invalid record without order_id: {record}")
                continue

            processed_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
            
            # Enrich and update record status
            order["status"] = "COMPLETED"
            order["processed_at"] = processed_at
            order["receipt_handle"] = record.get("receiptHandle", "")

            # 1. Save order to DynamoDB
            table.put_item(Item=order)
            print(f"Saved order {order_id} to DynamoDB table {TABLE_NAME}")

            # 2. Publish event notification to SNS
            sns_message = {
                "event": "OrderCompleted",
                "order_id": order_id,
                "customer_id": str(order.get("customer_id")),
                "total_amount": float(order.get("total_amount", 0)),
                "status": "COMPLETED",
                "timestamp": processed_at
            }
            sns.publish(
                TopicArn=TOPIC_ARN,
                Subject=f"Order Completed: {order_id}",
                Message=json.dumps(sns_message),
                MessageAttributes={
                    "EventType": {
                        "DataType": "String",
                        "StringValue": "OrderCompleted"
                    }
                }
            )
            print(f"Published OrderCompleted event for {order_id} to {TOPIC_ARN}")

            processed_count += 1

        except Exception as e:
            print(f"Error processing record {record.get('messageId')}: {str(e)}")
            raise e  # Raising triggers retry and eventually routes to DLQ

    return {
        "statusCode": 200,
        "processed_records": processed_count
    }

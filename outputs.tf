output "api_endpoint" {
  description = "HTTP API Gateway endpoint URL"
  value       = aws_apigatewayv2_stage.default.invoke_url
}

output "orders_post_url" {
  description = "URL to POST new orders"
  value       = "${trimsuffix(aws_apigatewayv2_stage.default.invoke_url, "/")}/orders"
}

output "producer_lambda_arn" {
  description = "Producer Lambda function ARN"
  value       = aws_lambda_function.producer.arn
}

output "consumer_lambda_arn" {
  description = "Consumer Lambda function ARN"
  value       = aws_lambda_function.consumer.arn
}

output "sqs_orders_queue_url" {
  description = "Main SQS Orders Queue URL"
  value       = aws_sqs_queue.orders_queue.id
}

output "sqs_orders_queue_arn" {
  description = "Main SQS Orders Queue ARN"
  value       = aws_sqs_queue.orders_queue.arn
}

output "sqs_orders_dlq_url" {
  description = "Orders Dead Letter Queue URL"
  value       = aws_sqs_queue.orders_dlq.id
}

output "dynamodb_orders_table_name" {
  description = "DynamoDB Orders Table Name"
  value       = aws_dynamodb_table.orders.name
}

output "dynamodb_orders_table_arn" {
  description = "DynamoDB Orders Table ARN"
  value       = aws_dynamodb_table.orders.arn
}

output "sns_order_events_topic_arn" {
  description = "SNS Order Events Topic ARN"
  value       = aws_sns_topic.order_events.arn
}

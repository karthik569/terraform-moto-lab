# Dead Letter Queue (DLQ)
resource "aws_sqs_queue" "orders_dlq" {
  name                      = "${var.project_name}-orders-dlq"
  message_retention_seconds = 1209600 # 14 days

  tags = {
    Environment = var.environment
    Project     = var.project_name
    Type        = "DeadLetterQueue"
  }
}

# Main Orders Queue with Redrive Policy pointing to DLQ
resource "aws_sqs_queue" "orders_queue" {
  name                       = "${var.project_name}-orders-queue"
  delay_seconds              = 0
  max_message_size           = 262144
  message_retention_seconds  = 86400 # 1 day
  visibility_timeout_seconds = 30

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.orders_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

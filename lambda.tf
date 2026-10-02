# Package producer Lambda handler
data "archive_file" "producer_zip" {
  type        = "zip"
  source_file = "${path.module}/src/producer/handler.py"
  output_path = "${path.module}/dist/producer.zip"
}

# Package consumer Lambda handler
data "archive_file" "consumer_zip" {
  type        = "zip"
  source_file = "${path.module}/src/consumer/handler.py"
  output_path = "${path.module}/dist/consumer.zip"
}

# -------------------------------------------------------------
# IAM Role for Order Producer Lambda
# -------------------------------------------------------------
resource "aws_iam_role" "producer_role" {
  name = "${var.project_name}-producer-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "producer_policy" {
  name = "${var.project_name}-producer-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:SendMessage"
        ]
        Resource = aws_sqs_queue.orders_queue.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "producer_attach" {
  role       = aws_iam_role.producer_role.name
  policy_arn = aws_iam_policy.producer_policy.arn
}

# -------------------------------------------------------------
# IAM Role for Order Consumer Lambda
# -------------------------------------------------------------
resource "aws_iam_role" "consumer_role" {
  name = "${var.project_name}-consumer-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_policy" "consumer_policy" {
  name = "${var.project_name}-consumer-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.orders_queue.arn
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:GetItem"
        ]
        Resource = aws_dynamodb_table.orders.arn
      },
      {
        Effect = "Allow"
        Action = [
          "sns:Publish"
        ]
        Resource = aws_sns_topic.order_events.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "consumer_attach" {
  role       = aws_iam_role.consumer_role.name
  policy_arn = aws_iam_policy.consumer_policy.arn
}

# -------------------------------------------------------------
# Lambda Functions
# -------------------------------------------------------------
resource "aws_lambda_function" "producer" {
  function_name    = "${var.project_name}-producer"
  role             = aws_iam_role.producer_role.arn
  handler          = "handler.lambda_handler"
  runtime          = "python3.12"
  filename         = data.archive_file.producer_zip.output_path
  source_code_hash = data.archive_file.producer_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      ORDER_QUEUE_URL = aws_sqs_queue.orders_queue.id
    }
  }

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

resource "aws_lambda_function" "consumer" {
  function_name    = "${var.project_name}-consumer"
  role             = aws_iam_role.consumer_role.arn
  handler          = "handler.lambda_handler"
  runtime          = "python3.12"
  filename         = data.archive_file.consumer_zip.output_path
  source_code_hash = data.archive_file.consumer_zip.output_base64sha256
  timeout          = 30

  environment {
    variables = {
      ORDERS_TABLE_NAME      = aws_dynamodb_table.orders.name
      ORDER_EVENTS_TOPIC_ARN = aws_sns_topic.order_events.arn
    }
  }

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

# -------------------------------------------------------------
# SQS Event Source Mapping to Consumer Lambda
# -------------------------------------------------------------
resource "aws_lambda_event_source_mapping" "sqs_to_consumer" {
  event_source_arn = aws_sqs_queue.orders_queue.arn
  function_name    = aws_lambda_function.consumer.arn
  batch_size       = var.sqs_batch_size
  enabled          = true
}

variable "aws_region" {
  description = "AWS region for simulated deployment"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment"
  type        = string
  default     = "local"
}

variable "project_name" {
  description = "Project name prefix"
  type        = string
  default     = "serverless-orders"
}

variable "sqs_max_receive_count" {
  description = "Max receive count for SQS redrive policy before routing to DLQ"
  type        = number
  default     = 3
}

variable "sqs_batch_size" {
  description = "SQS batch size for Lambda event source mapping"
  type        = number
  default     = 10
}

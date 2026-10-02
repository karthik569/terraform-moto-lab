resource "aws_sns_topic" "order_events" {
  name = "${var.project_name}-order-events"

  tags = {
    Environment = var.environment
    Project     = var.project_name
    ManagedBy   = "Terraform"
  }
}

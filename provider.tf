terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }
}

provider "aws" {
  region                      = var.aws_region
  access_key                  = "mock_access_key"
  secret_key                  = "mock_secret_key"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  # Redirect AWS API calls to Moto server (port 5000)
  endpoints {
    apigateway     = "http://127.0.0.1:5000"
    apigatewayv2   = "http://127.0.0.1:5000"
    cloudwatch     = "http://127.0.0.1:5000"
    dynamodb       = "http://127.0.0.1:5000"
    ec2            = "http://127.0.0.1:5000"
    iam            = "http://127.0.0.1:5000"
    kms            = "http://127.0.0.1:5000"
    lambda         = "http://127.0.0.1:5000"
    s3             = "http://127.0.0.1:5000"
    secretsmanager = "http://127.0.0.1:5000"
    sns            = "http://127.0.0.1:5000"
    sqs            = "http://127.0.0.1:5000"
    ssm            = "http://127.0.0.1:5000"
  }
}

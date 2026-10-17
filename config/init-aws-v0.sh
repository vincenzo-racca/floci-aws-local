#!/bin/bash
set -euo pipefail

endpoint_url=http://localhost:4566

echo "Create SQS queue"
aws sqs create-queue --queue-name book-event-queue --endpoint-url "$endpoint_url"

echo "Create DynamoDB table"
aws dynamodb create-table \
    --table-name person \
    --attribute-definitions \
        AttributeName=id,AttributeType=S \
    --key-schema \
        AttributeName=id,KeyType=HASH \
    --provisioned-throughput \
        ReadCapacityUnits=5,WriteCapacityUnits=5 \
    --endpoint-url "$endpoint_url"

stream_arn=$(aws dynamodb describe-table \
    --table-name person \
    --query 'Table.LatestStreamArn' --output text \
    --endpoint-url "$endpoint_url")


echo "Create Parameter Store"
aws ssm put-parameter \
    --name "/config/floci-aws-local/env-value" \
    --value "local" \
    --type String \
    --endpoint-url "$endpoint_url"

# Example of outbox pattern
echo "Create DynamoDB order table"
aws dynamodb create-table \
    --table-name order \
    --attribute-definitions AttributeName=orderId,AttributeType=S \
    --key-schema AttributeName=orderId,KeyType=HASH \
    --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5 \
    --endpoint-url "$endpoint_url"

echo "Create DynamoDB stream destination queue"
queue_url=$(aws sqs create-queue \
    --queue-name from-order-to-payment-queue \
    --query QueueUrl --output text \
    --endpoint-url "$endpoint_url")
queue_arn=$(aws sqs get-queue-attributes \
    --queue-url "$queue_url" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' --output text \
    --endpoint-url "$endpoint_url")


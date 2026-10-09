#!/bin/bash

echo "Create SQS queue"
aws sqs create-queue --queue-name book-event-queue --endpoint-url http://localhost:4566

echo "Create DynamoDB table"
aws dynamodb create-table \
    --table-name person \
    --attribute-definitions \
        AttributeName=id,AttributeType=S \
    --key-schema \
        AttributeName=id,KeyType=HASH \
    --provisioned-throughput \
        ReadCapacityUnits=5,WriteCapacityUnits=5 \
    --endpoint-url http://localhost:4566

echo "Create Parameter Store"
aws ssm put-parameter \
    --name "/config/floci-aws-local/env-value" \
    --value "local" \
    --type String \
    --endpoint-url http://localhost:4566
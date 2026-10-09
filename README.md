# Floci AWS Local

This Spring Boot project demonstrates how to run AWS services locally with **Floci** and how to use **Floci with Testcontainers** for integration tests. In this project, Floci replaces LocalStack: the application connects to Floci's local AWS-compatible endpoint, while the tests start an isolated Floci container instead of a LocalStack container.

The examples use Spring Cloud AWS with:

- **DynamoDB:** create a `person` table, save a person, and read it back.
- **SQS:** create a `book-event-queue`, publish a book event, and consume it with an `@SqsListener`.
- **AWS Systems Manager Parameter Store:** create `/config/floci-aws-local/env-value`, import it into Spring configuration, and log its value.

## Requirements

- Java 25
- Docker with Docker Compose or Podman with Podman Compose

The Maven wrapper is included, so a separate Maven installation is not required.

## Run locally

Start Floci and let the initialization script create the example AWS resources:

```bash
docker compose up -d
```

Then start the Spring Boot application:

```bash
./mvnw spring-boot:run
```

The application uses the endpoint `http://localhost:4566`, region `us-east-1`, and test credentials configured in [`application.properties`](src/main/resources/application.properties). On startup, it writes and reads a DynamoDB item, sends a message to SQS for the listener to receive, and logs the Parameter Store value (`local`). The resources are created by [`config/init-aws.sh`](config/init-aws.sh), mounted as a ready hook in [`compose.yaml`](compose.yaml).

Stop the local container with:

```bash
docker compose down
```

## Run the integration test

With Docker running, execute:

```bash
./mvnw test
```

The test configuration creates a `FlociContainer` from `floci/floci:2.2.0-compat`, mounts the same initialization script, and points Spring Cloud AWS to the container's mapped endpoint. [`DbInitializerTests`](src/test/java/com/vincenzoracca/flociawslocal/dynamodb/DbInitializerTests.java) verifies that the application saved the expected person in DynamoDB. Docker Compose does not need to be started for this test.

The `-compat` image and LocalStack-style initialization hook path (`/etc/localstack/init/ready.d/`) allow this example to use Floci in place of LocalStack. The application and test dependencies use Floci directly; no LocalStack container is required.

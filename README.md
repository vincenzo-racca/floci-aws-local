# Floci AWS Local

This Spring Boot project demonstrates how to run AWS services locally with **Floci** and how to use **Floci with Testcontainers** for integration tests. In this project, Floci replaces LocalStack: the application connects to Floci's local AWS-compatible endpoint, while the integration tests start an isolated Floci container.

The examples use Spring Cloud AWS with:

- **DynamoDB:** save and read a person, persist orders, and store transactional outbox events.
- **SQS:** publish and consume book events through `book-event-queue` and payment events through `from-order-to-payment-queue`.
- **DynamoDB Streams and EventBridge Pipes:** forward `INSERT` records from the `order` table to the payment queue, transforming each record into the format expected by `PaymentEvent`.
- **AWS Systems Manager Parameter Store:** create `/config/floci-aws-local/env-value`, import it into Spring configuration, and log its value.

## Requirements

- Java 25
- Docker with Docker Compose or Podman with Podman Compose

The Maven wrapper is included, so a separate Maven installation is not required. The examples below use Docker Compose; use the equivalent Compose command for your container runtime.

## Run locally

Start Floci and let the initialization script create the example AWS resources:

```bash
docker compose up -d
```

The Compose setup starts `floci/floci:2.2.0-compat` and **floci-ui**. Open [http://localhost:4500](http://localhost:4500) to interact with the local AWS resources through its web UI. The UI connects to the Floci service started by the same Compose file.

Then start the Spring Boot application:

```bash
./mvnw spring-boot:run
```

The application uses the endpoint `http://localhost:4566`, region `us-east-1`, and test credentials configured in [`application.properties`](src/main/resources/application.properties). With the default properties, startup creates an order through **Outbox V2**, publishes and consumes a book event, and logs the Parameter Store value (`local`). The order's stream record is transformed by the pipe and delivered to the payment listener.

The resources are created by [`config/init-aws.sh`](config/init-aws.sh), mounted as a ready hook in [`compose.yaml`](compose.yaml):

- DynamoDB tables `person`, `order`, and `outboxevent`.
- DynamoDB Streams on `order`, with `NEW_AND_OLD_IMAGES`.
- The `aggregationTypeStatus-index` global secondary index on `outboxevent` and TTL on its `ttl` attribute.
- SQS queues `book-event-queue` and `from-order-to-payment-queue`.
- The running `order-inserts-to-sqs` pipe, filtering for `INSERT` records from `order`.
- The Parameter Store value `/config/floci-aws-local/env-value`.

Stop the local containers with:

```bash
docker compose down
```

## Select an example

The initializers are conditional Spring components controlled by these properties:

| Property | Default | Behavior when enabled |
| --- | --- | --- |
| `floci-aws-local.enable-db-initializer` | `false` | Save and read a person from the `person` table. |
| `floci-aws-local.enable-outbox-v0` | `false` | Write an order, then send its payment event directly to SQS. |
| `floci-aws-local.enable-outbox-v1` | `false` | Write an order and outbox event transactionally, then publish due events through a scheduled poller. |
| `floci-aws-local.enable-outbox-v2` | `true` | Write an order and let DynamoDB Streams and EventBridge Pipes publish its payment event. |

An initializer is inactive when its property is `false` or absent. The defaults above come from `application.properties`. Enable one outbox version at a time to keep the examples separate. Book messaging and Parameter Store logging run independently of these flags.

The activation properties control Spring beans. The initialization script always creates the running pipe, so order inserts from V0 or V1 also reach the payment queue through the pipe. To run V0 or V1 on its own, stop the pipe before starting that example. With the Compose container running:

```bash
docker exec floci aws pipes stop-pipe \
    --name order-inserts-to-sqs \
    --endpoint-url http://localhost:4566
```

For example, select V1 with:

```bash
./mvnw spring-boot:run \
    -Dspring-boot.run.arguments="--floci-aws-local.enable-outbox-v2=false --floci-aws-local.enable-outbox-v1=true"
```

Before switching back to V2, restart the pipe:

```bash
docker exec floci aws pipes start-pipe \
    --name order-inserts-to-sqs \
    --endpoint-url http://localhost:4566
```

See the AWS CLI reference for [stopping a pipe](https://docs.aws.amazon.com/cli/latest/reference/pipes/stop-pipe.html).

### Outbox V0: direct publication

[`OutboxInitializerV0`](src/main/java/com/vincenzoracca/flociawslocal/outbox/v0/OutboxInitializerV0.java) writes an order to DynamoDB and then sends a `PaymentEvent` to SQS. These are separate operations: a successful database write does not guarantee that the message is sent if the application fails between them.

### Outbox V1: transactional outbox and polling

[`OutboxInitializerV1`](src/main/java/com/vincenzoracca/flociawslocal/outbox/v1/OutboxInitializerV1.java) writes an order and its `OrderCreated` outbox event in one DynamoDB transaction. The event's `payload` is a JSON string, which the poller deserializes as `Order` with Spring's Jackson 3 mapper.

The poller runs with a five-second fixed delay between executions. It queries all four READY shards (`Order#READY#0` through `Order#READY#3`) using `aggregationTypeStatus-index`, with `nextAttemptAt <= now` as a sort-key condition. The current time is fixed for each shard query, the page limit is 100, and the SDK paginator visits every page.

For each event, the poller sends a `PaymentEvent` to `from-order-to-payment-queue`. On success, it records `SENT`, changes the partition value to `Order#SENT#<shard>`, and sets `ttl` to one day after publication. On a payload or send failure, it increments `attempts` and schedules the next attempt 60 seconds later. The third failure records `DEAD` under `Order#DEAD#0`; these events are retained for inspection.

The final database update is conditional on the stored status still being `READY`. Publication occurs before this update, so concurrent workers or a failed status write after a successful send can still produce duplicate payments.

The outbox schema is defined by [`OutboxEvent`](src/main/java/com/vincenzoracca/flociawslocal/dynamodb/OutboxEvent.java):

| Resource | Key or attribute | DynamoDB type |
| --- | --- | --- |
| `outboxevent` table | Partition key `eventId` | String |
| `aggregationTypeStatus-index` | Partition key `aggregationTypeStatus` | String |
| `aggregationTypeStatus-index` | Sort key `nextAttemptAt` | Number, Unix epoch seconds (`Long` in Java) |
| Table TTL | `ttl` | Number, Unix epoch seconds (`Long` in Java) |

The index projects `ALL` attributes and uses read/write capacity 5. The initialization script creates resources; it does not migrate existing tables or records. When reusing an older environment, align the index schema and stored attributes with this definition, including numeric `nextAttemptAt` values and sharded partition values.

### Outbox V2: DynamoDB Streams and EventBridge Pipes

[`OutboxInitializerV2`](src/main/java/com/vincenzoracca/flociawslocal/outbox/v2/OutboxInitializerV2.java) only writes the order. The `order-inserts-to-sqs` pipe reads the resulting DynamoDB Stream `INSERT` record and forwards it to `from-order-to-payment-queue`.

The pipe's target `InputTemplate` maps:

- `dynamodb.NewImage.orderId.S` to `orderId`.
- `dynamodb.NewImage.price.N` to `amount`.

The listener receives a `PaymentEvent` containing the order ID and amount. This version has no direct `SqsTemplate.send()` call and does not use the `outboxevent` table or the V1 poller. Without the input transformation, the stream envelope would not match `PaymentEvent`, leaving its fields `null`.

## Run the tests

With Docker or Podman running, execute:

```bash
./mvnw clean test
```

The integration test configuration creates a `FlociContainer` from `floci/floci:2.2.0-compat`, mounts the same initialization script, and points Spring Cloud AWS to the container's mapped endpoint. Docker Compose does not need to be started for the tests.

Ryuk remains enabled for container cleanup. For environments that require privileged Ryuk, configure `~/.testcontainers.properties` with:

```properties
ryuk.container.privileged=true
```

See the [Testcontainers configuration reference](https://java.testcontainers.org/features/configuration/#customizing-ryuk-resource-reaper).

The suite includes:

- [`InitializerActivationTests`](src/test/java/com/vincenzoracca/flociawslocal/config/InitializerActivationTests.java): verify activation for `true`, `false`, and missing `floci-aws-local.*` properties.
- [`DbInitializerTests`](src/test/java/com/vincenzoracca/flociawslocal/dynamodb/DbInitializerTests.java): enable only the person initializer and verify the saved DynamoDB item.
- [`OutboxInitializerV1Tests`](src/test/java/com/vincenzoracca/flociawslocal/outbox/v1/OutboxInitializerV1Tests.java): exercise the poller with a mocked DynamoDB client and SQS template, covering pagination, due-time conditions, publication, retries, and failures.
- [`OutboxInitializerV2Tests`](src/test/java/com/vincenzoracca/flociawslocal/outbox/v2/OutboxInitializerV2Tests.java): enable only V2, verify the persisted order and payment received through the pipe, and check that the application did not send the payment directly through `SqsTemplate`.

The `-compat` image and LocalStack-style initialization hook path (`/etc/localstack/init/ready.d/`) allow this example to use Floci in place of LocalStack. The application and test dependencies use Floci directly; no LocalStack container is required.

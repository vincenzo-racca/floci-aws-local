package com.vincenzoracca.flociawslocal.outbox.v1;

import com.vincenzoracca.flociawslocal.dynamodb.Order;
import com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent;
import com.vincenzoracca.flociawslocal.sqs.PaymentEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.vincenzoracca.flociawslocal.dynamodb.Order.OrderStatus.PENDING;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.AGGREGATION_TYPE;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.NEXT_ATTEMPT_DELAY_SECOND;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.OutboxEventStatus.*;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.OutboxKeys.SHARD_COUNT;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.OutboxKeys.aggregationTypeStatus;
import static software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.sortLessThanOrEqualTo;

@Component
@ConditionalOnBooleanProperty(prefix = "floci-aws-local", name = "enable-outbox-v1")
public class OutboxInitializerV1 {

    private static final Logger log = LoggerFactory.getLogger(OutboxInitializerV1.class);

    private final DynamoDbTable<Order> orderTable;
    private final DynamoDbTable<OutboxEvent> outboxEventTable;
    private final DynamoDbEnhancedClient enhancedClient;
    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;

    public OutboxInitializerV1(DynamoDbEnhancedClient dynamoDbEnhancedClient, SqsTemplate sqsTemplate,
                              ObjectMapper objectMapper) {
        this.enhancedClient = dynamoDbEnhancedClient;
        this.orderTable = dynamoDbEnhancedClient.table("order", TableSchema.fromBean(Order.class));
        this.outboxEventTable = dynamoDbEnhancedClient.table("outboxevent", TableSchema.fromBean(OutboxEvent.class));
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        Order order =Order.builder()
                .orderId(UUID.randomUUID().toString())
                .bookIsbn("1234567890")
                .price(BigDecimal.valueOf(19.99))
                .status(PENDING)
                .build();

        String eventId = UUID.randomUUID().toString();
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .payload(objectMapper.writeValueAsString(order))
                .aggregateType(orderTable.tableName())
                .aggregateId(order.getOrderId())
                .eventId(eventId)
                .eventType("OrderCreated")
                .status(READY)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now().getEpochSecond())
                .aggregationTypeStatus(aggregationTypeStatus(AGGREGATION_TYPE,
                        READY, eventId))
                .build();

        log.info("Inserting order into DynamoDB: {}", order);

        enhancedClient.transactWriteItems(r -> {
            r.addPutItem(orderTable, order);
            r.addPutItem(outboxEventTable, outboxEvent);
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        log.debug("Processing outbox events");
        DynamoDbIndex<OutboxEvent> index = outboxEventTable.index("aggregationTypeStatus-index");

        IntStream.range(0, SHARD_COUNT)
                .forEach(shard ->processPage(index, shard));
    }

    private void processPage(DynamoDbIndex<OutboxEvent> index, int shard) {
        String pk = aggregationTypeStatus(AGGREGATION_TYPE, READY, shard);
        long now = Instant.now().getEpochSecond();

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(sortLessThanOrEqualTo(
                        k -> k.partitionValue(pk).sortValue(now)))
                .limit(100)
                .build();

        index.query(request)
                .forEach(page -> page.items().forEach(this::processEvent));
    }


    private void processEvent(OutboxEvent outboxEvent) {

        try {
            log.info("Processing outbox event: {}", outboxEvent);
            Order order = objectMapper.readValue(outboxEvent.getPayload(), Order.class);
            PaymentEvent paymentEvent = new PaymentEvent();
            paymentEvent.setOrderId(order.getOrderId());
            paymentEvent.setAmount(order.getPrice());
            sqsTemplate.send("from-order-to-payment-queue", paymentEvent);

            outboxEvent.setStatus(SENT);
            outboxEvent.setAggregationTypeStatus(aggregationTypeStatus(AGGREGATION_TYPE, SENT, outboxEvent.getEventId()));
            outboxEvent.setTtl(Instant.now().getEpochSecond() + 3600 * 24); // Set TTL to 1 day from now
        } catch (RuntimeException e) {
            log.warn("Failed to process outbox event {}", outboxEvent.getEventId(), e);
            outboxEvent.setAttempts(outboxEvent.getAttempts() + 1);
            outboxEvent.setNextAttemptAt(Instant.now().plusSeconds(NEXT_ATTEMPT_DELAY_SECOND).getEpochSecond());
            if (outboxEvent.getAttempts() >= 3) {
                outboxEvent.setStatus(DEAD);
                outboxEvent.setAggregationTypeStatus(aggregationTypeStatus(AGGREGATION_TYPE, DEAD, 0));
                log.error("Outbox event {} has failed 3 times and is marked as DEAD", outboxEvent.getEventId());
            }
        }
        finally {
            doConditionalUpdate(outboxEvent);
        }
    }

    private void doConditionalUpdate(OutboxEvent outboxEvent) {
        try {
            outboxEventTable.updateItem(r -> r.item(outboxEvent)
                    .conditionExpression(Expression.builder()
                            .expression("#s = :ready")
                            .putExpressionName("#s", "status")
                            .putExpressionValue(":ready", AttributeValue.fromS("READY"))
                            .build()));
        }
        catch (ConditionalCheckFailedException e){
            log.debug("Outbox event {} already handled by another instance", outboxEvent.getEventId());
        }

    }

    @SqsListener("from-order-to-payment-queue")
    public void listen(PaymentEvent paymentEvent) {
        log.info("Received payment event for order: {}, amount: {}", paymentEvent.getOrderId(), paymentEvent.getAmount());
        // other processing logic
    }
}

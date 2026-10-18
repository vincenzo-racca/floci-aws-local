package com.vincenzoracca.flociawslocal.outbox.v1;

import com.vincenzoracca.flociawslocal.dynamodb.Order;
import com.vincenzoracca.flociawslocal.sqs.PaymentEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.OutboxEventStatus.*;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxKeys.SHARD_COUNT;
import static com.vincenzoracca.flociawslocal.dynamodb.OutboxKeys.aggregationTypeStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxInitializerV1Tests {

    private static final QueryResponse EMPTY_PAGE = QueryResponse.builder().build();
    private static final String QUEUE = "from-order-to-payment-queue";

    private DynamoDbClient dynamoDbClient;
    private SqsTemplate sqsTemplate;
    private ObjectMapper objectMapper;
    private OutboxInitializerV1 initializer;

    @BeforeEach
    void setUp() {
        dynamoDbClient = mock(DynamoDbClient.class);
        sqsTemplate = mock(SqsTemplate.class);
        objectMapper = new ObjectMapper();
        initializer = new OutboxInitializerV1(
                DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build(),
                sqsTemplate, objectMapper);
        when(dynamoDbClient.queryPaginator(any(QueryRequest.class))).thenCallRealMethod();
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(EMPTY_PAGE);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());
    }

    @Test
    void initializationWritesTheOrderAndReadyEventInOneTransaction() {
        when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        initializer.initialize();

        ArgumentCaptor<TransactWriteItemsRequest> transaction =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDbClient).transactWriteItems(transaction.capture());
        var writes = transaction.getValue().transactItems();
        assertThat(writes).hasSize(2);
        assertThat(writes.get(0).put().tableName()).isEqualTo("order");
        Map<String, AttributeValue> order = writes.get(0).put().item();
        assertThat(order.get("orderId").s()).isNotBlank();
        assertThat(order.get("status").s()).isEqualTo("PENDING");
        assertThat(writes.get(1).put().tableName()).isEqualTo("outboxevent");
        Map<String, AttributeValue> event = writes.get(1).put().item();
        assertThat(event.get("eventId").s()).isNotBlank();
        assertThat(event.get("aggregateId")).isEqualTo(order.get("orderId"));
        assertThat(event.get("status").s()).isEqualTo("READY");
        assertThat(event.get("aggregationTypeStatus").s())
                .isEqualTo(aggregationTypeStatus("Order", READY, event.get("eventId").s()));
        Order payload = objectMapper.readValue(event.get("payload").s(), Order.class);
        assertThat(payload.getOrderId()).isEqualTo(order.get("orderId").s());
        assertThat(payload.getPrice()).isEqualByComparingTo("19.99");
    }

    @Test
    void firstQueryOmitsTheStartingKeyAndAnEmptyResultDoesNotPublishOrUpdate() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().build());

        initializer.processOutbox();

        List<QueryRequest> requests = queries(SHARD_COUNT);
        QueryRequest query = requests.getFirst();
        assertThat(query.hasExclusiveStartKey()).isFalse();
        assertThat(query.tableName()).isEqualTo("outboxevent");
        assertThat(query.indexName()).isEqualTo("aggregationTypeStatus-index");
        assertThat(query.limit()).isEqualTo(100);
        assertThat(query.expressionAttributeValues()).containsValue(AttributeValue.fromS("Order#READY#0"));
        verifyNoInteractions(sqsTemplate);
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void successfulPublicationSendsThePaymentAndMarksTheEventAsSent() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(event("event-1", 1))).build(), EMPTY_PAGE);

        initializer.processOutbox();

        ArgumentCaptor<PaymentEvent> payment = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(sqsTemplate).send(eq(QUEUE), payment.capture());
        assertThat(payment.getValue().getOrderId()).isEqualTo("order-event-1");
        assertThat(payment.getValue().getAmount()).isEqualByComparingTo("19.99");
        UpdateItemRequest update = updates(1).getFirst();
        Map<String, AttributeValue> values = update.expressionAttributeValues();
        assertThat(update.key()).containsEntry("eventId", AttributeValue.fromS("event-1"));
        assertThat(values).containsValue(AttributeValue.fromS("SENT"));
        assertThat(values).containsValue(AttributeValue.fromS(aggregationTypeStatus("Order", SENT, "event-1")));
        assertThat(values).containsValue(AttributeValue.fromN("1"));
    }

    @Test
    void publicationFailureKeepsTheEventReadyAndIncrementsAttempts() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(event("event-1", 1))).build(), EMPTY_PAGE);
        doThrow(new IllegalStateException("SQS unavailable"))
                .when(sqsTemplate).send(eq(QUEUE), any(PaymentEvent.class));

        initializer.processOutbox();

        UpdateItemRequest update = updates(1).getFirst();
        Map<String, AttributeValue> values = update.expressionAttributeValues();
        assertThat(values).containsValue(AttributeValue.fromS("READY"));
        assertThat(values).containsValue(AttributeValue.fromS(aggregationTypeStatus("Order", READY, 0)));
        assertThat(values).containsValue(AttributeValue.fromN("2"));
    }

    @Test
    void thirdFailedAttemptMarksTheEventAsDead() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(event("event-1", 2))).build(), EMPTY_PAGE);
        doThrow(new IllegalStateException("SQS unavailable"))
                .when(sqsTemplate).send(eq(QUEUE), any(PaymentEvent.class));

        initializer.processOutbox();

        UpdateItemRequest update = updates(1).getFirst();
        Map<String, AttributeValue> values = update.expressionAttributeValues();
        assertThat(values).containsValue(AttributeValue.fromS("DEAD"));
        assertThat(values).containsValue(AttributeValue.fromS(aggregationTypeStatus("Order", DEAD, 0)));
        assertThat(values).containsValue(AttributeValue.fromN("3"));
    }

    @Test
    void invalidPayloadRegistersAFailureWithoutPublishing() {
        Map<String, AttributeValue> invalid = new HashMap<>(event("event-1", 0));
        invalid.put("payload", AttributeValue.fromS("invalid JSON"));
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(invalid)).build(), EMPTY_PAGE);

        initializer.processOutbox();

        verifyNoInteractions(sqsTemplate);
        UpdateItemRequest update = updates(1).getFirst();
        Map<String, AttributeValue> values = update.expressionAttributeValues();
        assertThat(values).containsValue(AttributeValue.fromS("READY"));
        assertThat(values).containsValue(AttributeValue.fromS(aggregationTypeStatus("Order", READY, 0)));
        assertThat(values).containsValue(AttributeValue.fromN("1"));
    }

    @Test
    void aPublicationFailureDoesNotPreventProcessingTheNextEvent() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(event("event-1", 0), event("event-2", 0))).build(), EMPTY_PAGE);
        when(sqsTemplate.send(eq(QUEUE), any(PaymentEvent.class)))
                .thenThrow(new IllegalStateException("SQS unavailable"))
                .thenReturn(null);

        initializer.processOutbox();

        verify(sqsTemplate, times(2)).send(eq(QUEUE), any(PaymentEvent.class));
        List<UpdateItemRequest> updates = updates(2);
        assertThat(updates.get(0).expressionAttributeValues()).containsValue(AttributeValue.fromS("READY"));
        assertThat(updates.get(1).key()).containsEntry("eventId", AttributeValue.fromS("event-2"));
        assertThat(updates.get(1).expressionAttributeValues()).containsValue(AttributeValue.fromS("SENT"));
    }

    @Test
    void allPagesAreProcessedInTheSamePollIncludingEmptyIntermediatePages() {
        Map<String, AttributeValue> firstCursor = cursor("event-1");
        Map<String, AttributeValue> secondCursor = cursor("event-2");
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder().items(List.of(event("event-1", 0)))
                                .lastEvaluatedKey(firstCursor).build(),
                        QueryResponse.builder().lastEvaluatedKey(secondCursor).build(),
                        QueryResponse.builder().items(List.of(event("event-3", 0)))
                                .lastEvaluatedKey(Map.of()).build(), EMPTY_PAGE);

        initializer.processOutbox();

        List<QueryRequest> queries = queries(SHARD_COUNT + 2);
        assertThat(queries.get(0).hasExclusiveStartKey()).isFalse();
        assertThat(queries.get(1).exclusiveStartKey()).isEqualTo(firstCursor);
        assertThat(queries.get(2).exclusiveStartKey()).isEqualTo(secondCursor);
        assertThat(queries.subList(0, 3)).allSatisfy(query -> {
            assertThat(query.indexName()).isEqualTo("aggregationTypeStatus-index");
            assertThat(query.limit()).isEqualTo(100);
            assertThat(query.expressionAttributeValues()).containsValue(AttributeValue.fromS("Order#READY#0"));
            assertThat(sortCutoff(query)).isEqualTo(sortCutoff(queries.getFirst()));
        });
        ArgumentCaptor<PaymentEvent> payments = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(sqsTemplate, times(2)).send(eq(QUEUE), payments.capture());
        assertThat(payments.getAllValues()).extracting(PaymentEvent::getOrderId)
                .containsExactly("order-event-1", "order-event-3");
        assertThat(updates(2)).allSatisfy(update ->
                assertThat(update.expressionAttributeValues()).containsValue(AttributeValue.fromS("SENT")));
    }

    @Test
    void theNextPollStartsWithoutThePreviousPollsCursor() {
        Map<String, AttributeValue> cursor = cursor("event-1");
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder().lastEvaluatedKey(cursor).build(),
                        QueryResponse.builder().build(),
                        QueryResponse.builder().build());

        initializer.processOutbox();
        initializer.processOutbox();

        List<QueryRequest> queries = queries(2 * SHARD_COUNT + 1);
        assertThat(queries.get(0).hasExclusiveStartKey()).isFalse();
        assertThat(queries.get(1).exclusiveStartKey()).isEqualTo(cursor);
        assertThat(queries.get(SHARD_COUNT + 1).hasExclusiveStartKey()).isFalse();
    }

    @Test
    void failureToPersistTheSentStatusPropagatesWithoutCountingAPublicationFailure() {
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(event("event-1", 0))).build(), EMPTY_PAGE);
        IllegalStateException failure = new IllegalStateException("DynamoDB unavailable");
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);

        assertThatThrownBy(initializer::processOutbox).isSameAs(failure);

        verify(sqsTemplate).send(eq(QUEUE), any(PaymentEvent.class));
        UpdateItemRequest update = updates(1).getFirst();
        Map<String, AttributeValue> values = update.expressionAttributeValues();
        assertThat(values).containsValue(AttributeValue.fromS("SENT"));
        assertThat(values).containsValue(AttributeValue.fromN("0"));
    }

    @Test
    void eachShardQueriesOnlyEventsWhoseNextAttemptIsDue() {
        long before = Instant.now().getEpochSecond();

        initializer.processOutbox();

        long after = Instant.now().getEpochSecond();
        List<QueryRequest> requests = queries(SHARD_COUNT);
        for (int shard = 0; shard < SHARD_COUNT; shard++) {
            QueryRequest query = requests.get(shard);
            assertThat(query.expressionAttributeValues())
                    .containsValue(AttributeValue.fromS(aggregationTypeStatus("Order", READY, shard)));
            assertThat(query.filterExpression()).isNull();
            assertThat(sortCutoff(query)).isBetween(before, after);
        }
    }

    private long sortCutoff(QueryRequest query) {
        String sortKeyAlias = query.expressionAttributeNames().entrySet().stream()
                .filter(entry -> entry.getValue().equals("nextAttemptAt"))
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();
        var matcher = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(sortKeyAlias) + " <= (:\\w+)")
                .matcher(query.keyConditionExpression());
        assertThat(matcher.find()).isTrue();
        AttributeValue cutoff = query.expressionAttributeValues().get(matcher.group(1));
        assertThat(cutoff.s()).isNull();
        assertThat(cutoff.n()).isNotBlank();
        return Long.parseLong(cutoff.n());
    }

    private Map<String, AttributeValue> event(String eventId, int attempts) {
        Order order = Order.builder()
                .orderId("order-" + eventId)
                .price(BigDecimal.valueOf(19.99))
                .build();
        return Map.of(
                "eventId", AttributeValue.fromS(eventId),
                "aggregateType", AttributeValue.fromS("Order"),
                "status", AttributeValue.fromS("READY"),
                "aggregationTypeStatus", AttributeValue.fromS("Order#READY#0"),
                "nextAttemptAt", AttributeValue.fromN("1767225600"),
                "payload", AttributeValue.fromS(objectMapper.writeValueAsString(order)),
                "attempts", AttributeValue.fromN(Integer.toString(attempts)));
    }

    private Map<String, AttributeValue> cursor(String eventId) {
        return Map.of(
                "eventId", AttributeValue.fromS(eventId),
                "aggregationTypeStatus", AttributeValue.fromS("Order#READY#0"),
                "nextAttemptAt", AttributeValue.fromN("1767225600"));
    }

    private List<QueryRequest> queries(int count) {
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient, times(count)).query(queries.capture());
        return queries.getAllValues();
    }

    private List<UpdateItemRequest> updates(int count) {
        ArgumentCaptor<UpdateItemRequest> updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient, times(count)).updateItem(updates.capture());
        return updates.getAllValues();
    }
}

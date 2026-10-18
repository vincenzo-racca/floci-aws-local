package com.vincenzoracca.flociawslocal.dynamodb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

@DynamoDbBean
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    public static final String ORDER_AGGREGATION_TYPE = "Order";
    public static final long NEXT_ATTEMPT_DELAY_SECOND = 60; // 1 minute

    private String eventId;
    private OutboxEventStatus status;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private Instant createdAt;
    private Long nextAttemptAt;
    private String aggregationTypeStatus;
    private int attempts;
    private String payload;
    private Long ttl;

    public enum OutboxEventStatus {
        READY, SENT, DEAD
    }

    @DynamoDbPartitionKey
    public String getEventId() {
        return eventId;
    }


    @DynamoDbSecondaryPartitionKey(indexNames = "aggregationTypeStatus-index")
    public String getAggregationTypeStatus() {
        return aggregationTypeStatus;
    }

    @DynamoDbSecondarySortKey(indexNames = "aggregationTypeStatus-index")
    public Long getNextAttemptAt() {
        return nextAttemptAt;
    }

}

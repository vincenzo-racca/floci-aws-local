package com.vincenzoracca.flociawslocal.dynamodb;

import com.vincenzoracca.flociawslocal.dynamodb.OutboxEvent.OutboxEventStatus;

public final class OutboxKeys {

    public static final int SHARD_COUNT = 4;

    private OutboxKeys() {}

    public static String aggregationTypeStatus(String aggregateType,
                                               OutboxEventStatus status,
                                               String eventId) {
        int shard = Math.floorMod(eventId.hashCode(), SHARD_COUNT);
        return aggregateType + "#" + status + "#" + shard;
    }

    public static String aggregationTypeStatus(String aggregateType,
                                               OutboxEventStatus status,
                                               int shard) {
        return aggregateType + "#" + status + "#" + shard;
    }
}

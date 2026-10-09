package com.vincenzoracca.flociawslocal.dynamodb;

import com.vincenzoracca.flociawslocal.model.PersonEntity;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.UUID;

@DynamoDbBean
public class Person extends PersonEntity {

    @DynamoDbPartitionKey
    @Override
    public UUID getId() {
        return super.getId();
    }
}

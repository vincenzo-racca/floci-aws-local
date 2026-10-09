package com.vincenzoracca.flociawslocal.dynamodb;

import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.UUID;

@Component
public class DbInitializer {

    private final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private final DynamoDbTemplate dynamoDbTemplate;

    public DbInitializer(DynamoDbTemplate dynamoDbTemplate) {
        this.dynamoDbTemplate = dynamoDbTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        Person entity = new Person();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setName("John Doe");
        entity.setLastName("Smith");

        dynamoDbTemplate.save(entity);

        Person entityFromDb = dynamoDbTemplate.load(
                Key.builder().partitionValue(id.toString()).build(),
                Person.class);

        log.info("Found Person from DynamoDb: {}", entityFromDb);
    }
}

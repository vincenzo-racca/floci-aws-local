package com.vincenzoracca.flociawslocal.sqs.consumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class PersonStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(PersonStreamConsumer.class);

    @SqsListener("from-dynnamodb-to-sqs-quee")
    public void listen(@Payload String payload) {
        log.info("DynamoDB INSERT event received from EventBridge Pipe: {}", payload);
    }
}

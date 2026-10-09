package com.vincenzoracca.flociawslocal.sqs.consumer;

import com.vincenzoracca.flociawslocal.model.Book;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BookConsumer {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @SqsListener("book-event-queue")
    public void listen(@Payload Book payload,@Headers Map<String, Object> headers) {
        log.info("Book event received, headers: {}, payload: {}", headers, payload);
    }
}

package com.vincenzoracca.flociawslocal.sqs.producer;

import com.vincenzoracca.flociawslocal.model.Book;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BookProducer {

    private final SqsTemplate sqsTemplate;

    public BookProducer(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void produce() {
        var book = new Book("isbn", "Name");
        sqsTemplate.send(to -> to.queue("book-event-queue")
                .payload(book)
                .header("eventId", UUID.randomUUID().toString())
                .delaySeconds(2)
        );


    }
}

package com.vincenzoracca.flociawslocal.outbox.v0;

import com.vincenzoracca.flociawslocal.dynamodb.Order;
import com.vincenzoracca.flociawslocal.sqs.PaymentEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static com.vincenzoracca.flociawslocal.dynamodb.Order.OrderStatus.PENDING;

@Component
@ConditionalOnBooleanProperty(prefix = "floci-aws-local", name = "enable-outbox-v0")
public class OutboxInitializerV0 {

    private static final Logger log = LoggerFactory.getLogger(OutboxInitializerV0.class);

    private final DynamoDbTable<Order> orderTable;
    private final SqsTemplate sqsTemplate;

    public OutboxInitializerV0(DynamoDbEnhancedClient dynamoDbEnhancedClient, SqsTemplate sqsTemplate) {
        this.orderTable = dynamoDbEnhancedClient.table("order", TableSchema.fromBean(Order.class));
        this.sqsTemplate = sqsTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .bookIsbn("1234567890")
                .price(BigDecimal.valueOf(19.99))
                .status(PENDING)
                .build();

        PutItemEnhancedRequest<Order> request = PutItemEnhancedRequest.builder(Order.class)
                .item(order)
                .build();
        log.info("Inserting order into DynamoDB: {}", order);
        orderTable.putItem(request);

        PaymentEvent paymentEvent = new PaymentEvent();
        paymentEvent.setOrderId(order.getOrderId());
        paymentEvent.setAmount(order.getPrice());
        sqsTemplate.send("from-order-to-payment-queue", paymentEvent);

    }

    @SqsListener("from-order-to-payment-queue")
    public void listen(PaymentEvent paymentEvent) {
        log.info("Received payment event for order: {}, amount: {}", paymentEvent.getOrderId(), paymentEvent.getAmount());
        //
    }
}

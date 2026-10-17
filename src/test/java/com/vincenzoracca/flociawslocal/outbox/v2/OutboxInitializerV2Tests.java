package com.vincenzoracca.flociawslocal.outbox.v2;

import com.vincenzoracca.flociawslocal.FlociConfiguration;
import com.vincenzoracca.flociawslocal.dynamodb.Order;
import com.vincenzoracca.flociawslocal.sqs.PaymentEvent;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "floci-aws-local.enable-db-initializer=false",
        "floci-aws-local.enable-outbox-v0=false",
        "floci-aws-local.enable-outbox-v1=false",
        "floci-aws-local.enable-outbox-v2=true"
})
@Import(FlociConfiguration.class)
class OutboxInitializerV2Tests {

    @Autowired
    private DynamoDbTemplate dynamoDbTemplate;

    @MockitoSpyBean
    private OutboxInitializerV2 initializer;

    @MockitoSpyBean
    private SqsTemplate sqsTemplate;

    @Test
    void startupPersistsTheOrderAndReceivesThePaymentTransformedByThePipe() {
        List<Order> orders = dynamoDbTemplate.scan(ScanEnhancedRequest.builder().build(), Order.class)
                .items().stream().toList();
        assertThat(orders).hasSize(1);
        Order order = orders.getFirst();
        assertThat(order.getOrderId()).isNotBlank();
        assertThat(order.getBookIsbn()).isEqualTo("1234567890");
        assertThat(order.getPrice()).isEqualByComparingTo("19.99");
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PENDING);

        ArgumentCaptor<PaymentEvent> payment = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(initializer, timeout(10_000)).listen(payment.capture());
        assertThat(payment.getValue().getOrderId()).isEqualTo(order.getOrderId());
        assertThat(payment.getValue().getAmount()).isEqualByComparingTo(order.getPrice());
        verify(sqsTemplate, never()).send(eq("from-order-to-payment-queue"), any(PaymentEvent.class));
    }
}

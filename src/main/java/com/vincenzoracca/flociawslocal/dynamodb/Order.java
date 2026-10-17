package com.vincenzoracca.flociawslocal.dynamodb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.math.BigDecimal;

@DynamoDbBean
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    private String orderId;
    private String bookIsbn;
    private BigDecimal price;
    private OrderStatus status;

    @DynamoDbPartitionKey
    public String getOrderId() {
        return orderId;
    }

    public enum OrderStatus {
        PENDING,
        COMPLETED,
        CANCELLED
    }
}

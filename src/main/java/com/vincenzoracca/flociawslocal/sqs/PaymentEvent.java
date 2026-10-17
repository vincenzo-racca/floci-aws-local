package com.vincenzoracca.flociawslocal.sqs;

import java.math.BigDecimal;
import java.util.Objects;

public class PaymentEvent {

    private String orderId;
    private BigDecimal amount;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PaymentEvent that)) return false;
        return Objects.equals(orderId, that.orderId) && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, amount);
    }

    @Override
    public String toString() {
        return "PaymentEvent{" +
                "orderId='" + orderId + '\'' +
                ", amount=" + amount +
                '}';
    }
}

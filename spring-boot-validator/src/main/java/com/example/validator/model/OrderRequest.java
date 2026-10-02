package com.example.validator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record OrderRequest(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("customer_id") @NotBlank String customerId,
    @JsonProperty("order_type") String orderType,
    @NotEmpty List<OrderItem> items,
    @JsonProperty("total_amount") @Positive double totalAmount
) {}

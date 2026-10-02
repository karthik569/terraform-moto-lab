package com.example.validator.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record OrderItem(
    @NotBlank String sku,
    @NotBlank String name,
    @Positive int qty,
    @Positive double price
) {}

package com.example.tutorplatform.dto.payment;

import jakarta.validation.constraints.NotBlank;

public record AdminMarkPaymentRequest(@NotBlank String reason) {}

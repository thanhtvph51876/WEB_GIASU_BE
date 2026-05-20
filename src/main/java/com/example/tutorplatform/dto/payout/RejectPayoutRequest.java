package com.example.tutorplatform.dto.payout;

import jakarta.validation.constraints.NotBlank;

public record RejectPayoutRequest(@NotBlank String reason) {}

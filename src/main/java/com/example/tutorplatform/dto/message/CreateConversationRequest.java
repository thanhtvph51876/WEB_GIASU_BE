package com.example.tutorplatform.dto.message;

import java.util.List;
import java.util.UUID;

public record CreateConversationRequest(
    String title,
    String type,
    UUID bookingId,
    UUID classId,
    String initialMessage,
    List<UUID> participantIds
) {}

package com.example.tutorplatform.dto.auth;

public record CurrentUserResponse(
    String id,
    String email,
    String fullName,
    String role,
    String status
) {}

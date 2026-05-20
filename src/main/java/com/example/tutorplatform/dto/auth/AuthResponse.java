package com.example.tutorplatform.dto.auth;

public record AuthResponse(String accessToken, String refreshToken, Object user) {}

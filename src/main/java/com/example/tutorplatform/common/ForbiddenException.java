package com.example.tutorplatform.common;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends BusinessException {
  public ForbiddenException(String message) {
    super("FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }
}

package com.example.tutorplatform.common;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BusinessException {
  public NotFoundException(String message) {
    super("NOT_FOUND", message, HttpStatus.NOT_FOUND);
  }
}

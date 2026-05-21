package com.example.tutorplatform.common;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
    return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.code(), ex.getMessage(), null));
  }

  @ExceptionHandler({AccessDeniedException.class})
  public ResponseEntity<ApiResponse<Void>> denied(Exception ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.error("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.", null));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
  public ResponseEntity<ApiResponse<Void>> validation(Exception ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.error("VALIDATION_ERROR", "Dữ liệu không hợp lệ.", Map.of("message", ex.getMessage())));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiResponse<Void>> duplicate(DuplicateKeyException ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.error("DUPLICATE_VALUE", "Dữ liệu đã tồn tại.", null));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error("INTERNAL_ERROR", "Có lỗi hệ thống, vui lòng thử lại.", null));
  }
}

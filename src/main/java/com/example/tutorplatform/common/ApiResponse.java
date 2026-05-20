package com.example.tutorplatform.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    PageMetadata pagination,
    ApiError error
) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null, null, null);
  }

  public static <T> ApiResponse<T> ok(T data, String message) {
    return new ApiResponse<>(true, data, message, null, null);
  }

  public static <T> ApiResponse<T> page(T data, PageMetadata pagination) {
    return new ApiResponse<>(true, data, null, pagination, null);
  }

  public static ApiResponse<Void> error(String code, String message, Object details) {
    return new ApiResponse<>(false, null, null, null, new ApiError(code, message, details));
  }
}

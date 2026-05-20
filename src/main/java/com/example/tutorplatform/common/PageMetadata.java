package com.example.tutorplatform.common;

public record PageMetadata(int page, int pageSize, long total, int totalPages) {
  public static PageMetadata of(int page, int pageSize, long total) {
    int totalPages = pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
    return new PageMetadata(page, pageSize, total, totalPages);
  }
}

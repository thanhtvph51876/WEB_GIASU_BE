package com.example.tutorplatform.dto.file;

public record UploadedFileResponse(
    String id,
    String fileId,
    String fileName,
    String originalFileName,
    String fileUrl,
    long fileSize,
    String mimeType,
    String visibility
) {}

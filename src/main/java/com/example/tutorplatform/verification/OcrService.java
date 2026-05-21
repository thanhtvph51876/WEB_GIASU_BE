package com.example.tutorplatform.verification;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OcrService {
  public Map<String, Object> extractDocumentFields(String fileId) {
    // MVP placeholder. Production can connect OCR/IDP here without changing API contracts.
    return Map.of(
        "fileId", fileId,
        "ocrConfidence", 0,
        "provider", "none"
    );
  }
}

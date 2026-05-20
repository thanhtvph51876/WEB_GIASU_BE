package com.example.tutorplatform.dto.session;

import java.util.LinkedHashMap;
import java.util.Map;

public record CompleteSessionRequest(String note, String tutorNote) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    if (note != null) body.put("note", note);
    if (tutorNote != null) body.put("tutorNote", tutorNote);
    return body;
  }
}

package com.example.tutorplatform.dto.booking;

import java.util.LinkedHashMap;
import java.util.Map;

public record CompleteTrialRequest(String resultNote, String note) {
  public Map<String, Object> toMap() {
    Map<String, Object> body = new LinkedHashMap<>();
    if (resultNote != null) body.put("resultNote", resultNote);
    if (note != null) body.put("note", note);
    return body;
  }
}

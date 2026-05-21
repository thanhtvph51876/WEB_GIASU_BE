package com.example.tutorplatform.verification;

import org.springframework.stereotype.Service;

@Service
public class FraudRiskService {
  public int score(boolean duplicateFile, String schoolEmail, String studentCode) {
    int score = duplicateFile ? 70 : 0;
    if (schoolEmail == null || schoolEmail.isBlank()) score += 5;
    if (studentCode == null || studentCode.isBlank()) score += 10;
    return Math.min(100, score);
  }
}

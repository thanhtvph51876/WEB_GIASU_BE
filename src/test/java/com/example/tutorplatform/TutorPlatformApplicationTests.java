package com.example.tutorplatform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TutorPlatformApplicationTests {
  @Test
  void contextShapeIsPresent() {
    assertThat(TutorPlatformApplication.class.getPackageName()).isEqualTo("com.example.tutorplatform");
  }
}

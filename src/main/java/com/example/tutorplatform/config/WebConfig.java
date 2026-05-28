package com.example.tutorplatform.config;

import com.example.tutorplatform.security.AdminPermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AppProperties properties;
  private final AdminPermissionInterceptor adminPermissionInterceptor;

  public WebConfig(AppProperties properties, AdminPermissionInterceptor adminPermissionInterceptor) {
    this.properties = properties;
    this.adminPermissionInterceptor = adminPermissionInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(adminPermissionInterceptor).addPathPatterns("/api/v1/admin/**");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // Files are served only through FileController so object-level authorization
    // can be enforced for private tutor documents and other sensitive uploads.
  }
}

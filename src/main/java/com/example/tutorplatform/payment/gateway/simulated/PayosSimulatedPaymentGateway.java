package com.example.tutorplatform.payment.gateway.simulated;

import com.example.tutorplatform.payment.gateway.PaymentGatewayType;
import org.springframework.stereotype.Component;

@Component
public class PayosSimulatedPaymentGateway extends AbstractSimulatedPaymentGateway {
  @Override
  public PaymentGatewayType type() {
    return PaymentGatewayType.PAYOS;
  }
}

package com.example.tutorplatform.payment.gateway.simulated;

import com.example.tutorplatform.payment.gateway.PaymentGatewayType;
import org.springframework.stereotype.Component;

@Component
public class MomoSimulatedPaymentGateway extends AbstractSimulatedPaymentGateway {
  @Override
  public PaymentGatewayType type() {
    return PaymentGatewayType.MOMO;
  }
}

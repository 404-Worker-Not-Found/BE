package com.workernotfound.payment.domain.payout.service;

import java.time.Clock;
import com.workernotfound.payment.domain.payout.PayoutProperties;
import org.springframework.context.annotation.*;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(PayoutProperties.class)
public class PayoutConfig {
  @Bean
  Clock payoutClock() { return Clock.systemUTC(); }
}

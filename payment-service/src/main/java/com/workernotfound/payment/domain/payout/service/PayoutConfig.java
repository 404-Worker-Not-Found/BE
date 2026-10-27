package com.workernotfound.payment.domain.payout.service;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class PayoutConfig {
  @Bean
  Clock payoutClock() { return Clock.systemUTC(); }
}

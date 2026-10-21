package com.workernotfound.payment.domain.refund.repository;

import com.workernotfound.payment.domain.refund.entity.PaymentRefund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {}

package com.workernotfound.payment.domain.payment.service;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.*;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.*;
import com.workernotfound.payment.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {
  private final PaymentLockRepository payments;
  private final PaymentDepositRepository deposits;
  private final PaymentCommandRepository commands;

  @Transactional
  public Outcome lock(String key, PaymentLockRequest request) {
    var command = lockCommand(key, request.fingerprintPayload());
    if (command.paymentId() != null || command.rejection() != null) return outcome(command);
    try {
      return createLock(key, request);
    } catch (BusinessException exception) {
      return reject(key, exception);
    }
  }

  private Outcome createLock(String key, PaymentLockRequest request) {
    if (commands.lockMatching(request.matchingId()) != null)
      throw new BusinessException(PaymentErrorCode.ACTIVE_LOCK_EXISTS);
    PaymentDeposit deposit = lockDeposit(request.jobPostId());
    deposit.reserve(request.ownerMemberId(), request.currency(), request.amount());
    PaymentLock payment = payments.saveAndFlush(newPayment(request, deposit.getId()));
    commands.activate(request.matchingId(), payment.getId());
    commands.history(payment.getId(), null, "LOCKED", key);
    commands.complete(key, payment.getId());
    return new Outcome(payment.getId(), null);
  }

  @Transactional
  public Outcome release(String key, Long paymentId) {
    var command = lockCommand(key, "RELEASE:v1:" + paymentId);
    if (command.paymentId() != null || command.rejection() != null) return outcome(command);
    try {
      return releaseLock(key, paymentId);
    } catch (BusinessException exception) {
      return reject(key, exception);
    }
  }

  private Outcome releaseLock(String key, Long paymentId) {
    var references = payments.findReferencesById(paymentId)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    commands.lockMatching(references.getMatchingId());
    PaymentDeposit deposit = lockDeposit(references.getJobPostId());
    PaymentLock payment = payments.findByIdForUpdate(paymentId)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    if (payment.release()) {
      deposit.release(payment.getAmount());
      commands.history(paymentId, "LOCKED", "RELEASED", key);
      commands.release(payment.getMatchingId(), paymentId);
    }
    commands.complete(key, paymentId);
    return new Outcome(paymentId, null);
  }

  public record Outcome(Long paymentId, PaymentErrorCode rejection) {}

  private Outcome outcome(PaymentCommandRepository.Command command) {
    return new Outcome(command.paymentId(), command.rejection() == null
        ? null : PaymentErrorCode.valueOf(command.rejection()));
  }

  // Business rejections occur before balance mutations. Persist them so delayed retries
  // cannot acquire funds after the Saga has already compensated a definitive rejection.
  private Outcome reject(String key, BusinessException exception) {
    PaymentErrorCode rejection = (PaymentErrorCode) exception.getErrorCode();
    commands.reject(key, rejection.name());
    return new Outcome(null, rejection);
  }

  private PaymentDeposit lockDeposit(Long jobPostId) {
    return deposits.findByJobPostIdForUpdate(jobPostId)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.DEPOSIT_NOT_FOUND));
  }

  private PaymentLock newPayment(PaymentLockRequest request, Long depositId) {
    return PaymentLock.builder().depositId(depositId).matchingId(request.matchingId())
        .jobPostId(request.jobPostId()).ownerMemberId(request.ownerMemberId())
        .workerMemberId(request.workerMemberId()).amount(request.amount())
        .currency(request.currency()).build();
  }

  private PaymentCommandRepository.Command lockCommand(String key, String payload) {
    String fingerprint = fingerprint(payload);
    var command = commands.lockCommand(key, fingerprint);
    if (!command.fingerprint().equals(fingerprint))
      throw new BusinessException(PaymentErrorCode.COMMAND_CONFLICT);
    return command;
  }

  private String fingerprint(String payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}

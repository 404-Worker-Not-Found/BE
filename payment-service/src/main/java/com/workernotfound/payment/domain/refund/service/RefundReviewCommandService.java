package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.refund.dto.request.RefundReviewResumeRequest;
import com.workernotfound.payment.domain.refund.repository.RefundReviewRepository;
import com.workernotfound.payment.domain.refund.repository.RefundReviewRepository.RefundState;
import com.workernotfound.payment.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자가 확인한 검토 대상 환불을 같은 환불의 자동 실행으로 되돌린다. 금액·상태는 바꾸지 않고 PG도 호출하지 않는다.
 *
 * <p>재개한 환불은 다음 실행 주기에 결제를 먼저 조회해 이미 반영된 취소를 성공으로 확정하고, 반영되지 않았을 때만 같은 멱등 키로 다시
 * 보낸다. 업무 거절은 환불을 바꾸기 전에 판단하고 명령에 영속화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReviewCommandService {
  private static final String RESUMED = "REVIEW_RESUMED";
  private final RefundReviewRepository reviews;

  /** @return 업무 거절 코드. 재개했거나 같은 키로 이미 재개했으면 비어 있다. */
  @Transactional
  public Optional<PaymentErrorCode> resume(Long refundId, String key, RefundReviewResumeRequest request) {
    Long depositId = reviews.findDepositId(refundId)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
    // 명령 저장의 FK 검사가 환불 행을 공유 잠금하므로 예치를 먼저 잠가야 예치 → 환불 순서가 유지된다.
    boolean isFundingBlocked = reviews.lockFundingBlocked(depositId);
    var command = lockCommand(key, refundId, request);
    if (command.isResolved()) return Optional.ofNullable(command.rejection()).map(PaymentErrorCode::valueOf);
    RefundState refund = reviews.lockRefund(refundId);
    Optional<PaymentErrorCode> rejection = rejection(refund, request.reviewReason(), isFundingBlocked);
    if (rejection.isPresent()) {
      reviews.rejectCommand(key, rejection.get().name());
      return rejection;
    }
    reviews.resume(refundId);
    reviews.history(refundId, RESUMED);
    reviews.completeCommand(key, refund.reviewReason());
    log.info("검토 대상 환불 자동 실행 재개: refundId={}, reviewReason={}, operatorId={}", refundId,
        refund.reviewReason(), request.operatorId());
    return Optional.empty();
  }

  // 운영자가 확인한 사유와 지금 사유가 같아야 한다. 설명되지 않는 취소와 차단된 예치는 장부 복구가 먼저다.
  private Optional<PaymentErrorCode> rejection(RefundState refund, String reviewReason, boolean isFundingBlocked) {
    if (!refund.isUnderReview() || !reviewReason.equals(refund.reviewReason()))
      return Optional.of(PaymentErrorCode.REFUND_REVIEW_MISMATCH);
    if (!RefundReviewPolicy.isResumableReason(refund.reviewReason()))
      return Optional.of(PaymentErrorCode.REFUND_REVIEW_NOT_RESUMABLE);
    if (isFundingBlocked) return Optional.of(PaymentErrorCode.FUNDING_BLOCKED);
    return Optional.empty();
  }

  private RefundReviewRepository.Command lockCommand(
      String key, Long refundId, RefundReviewResumeRequest request) {
    String fingerprint = fingerprint(request.fingerprintPayload(refundId));
    var command = reviews.lockCommand(key, fingerprint, refundId, request.operatorId(), request.note());
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

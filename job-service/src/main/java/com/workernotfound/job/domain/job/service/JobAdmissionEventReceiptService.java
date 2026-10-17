package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobAdmissionEventReceipt;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.exception.InvalidAdmissionEventException;
import com.workernotfound.job.domain.job.repository.JobAdmissionEventReceiptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ApplicationSubmitted}를 eventId 기준으로 한 번만 처리한다. 승인 사용 완료 처리와 수신 기록·결과를 한 트랜잭션에 저장한다.
 *
 * <p>같은 eventId의 동시 처리는 승인 행 잠금과 eventId 유일 제약으로 막는다. 늦게 들어온 처리는 앞선 처리의 커밋 뒤
 * {@code ALREADY_CONSUMED}를 얻고 수신 기록 저장에서 유일 제약 위반으로 롤백된다. 롤백된 처리는 ACK되지 않고 다음 pending 재수신에서
 * 중복으로 판단된다. 따라서 승인 상태와 consumedAt은 처음 커밋한 처리만 정한다.
 *
 * <p>DB 장애 같은 일시 오류는 예외로 전파되어 아무것도 기록하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JobAdmissionEventReceiptService {

    private final JobAdmissionEventReceiptRepository receiptRepository;
    private final JobApplicationAdmissionConsumptionService consumptionService;
    private final Clock clock;

    @Transactional
    public AdmissionEventOutcome receive(ApplicationSubmittedEvent event) {
        Optional<JobAdmissionEventReceipt> receipt = receiptRepository.findByEventId(event.eventId());
        if (receipt.isPresent()) {
            return replay(receipt.get(), event);
        }
        AdmissionConsumptionResult result = consumptionService.consume(event.toConsumptionCommand());
        receiptRepository.saveAndFlush(JobAdmissionEventReceipt.builder()
                .event(event)
                .result(result)
                .receivedAt(LocalDateTime.now(clock))
                .build());
        return new AdmissionEventOutcome(result, false);
    }

    private AdmissionEventOutcome replay(JobAdmissionEventReceipt receipt, ApplicationSubmittedEvent event) {
        if (!receipt.isSameEvent(event)) {
            throw new InvalidAdmissionEventException("같은 eventId에 다른 내용의 ApplicationSubmitted가 전달되었습니다.");
        }
        return new AdmissionEventOutcome(receipt.getResult(), true);
    }
}

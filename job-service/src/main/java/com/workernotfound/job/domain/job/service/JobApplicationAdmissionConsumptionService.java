package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ApplicationSubmitted}로 전달된 지원 저장 사실을 받아 지원 접수 승인을 사용 완료로 처리한다.
 *
 * <p>승인 행만 잠그고 공고 행은 잠그지 않는다. 승인 발급은 공고 행을 잠근 뒤 새 승인을 저장할 뿐 기존 승인 행을 잠그지 않으므로
 * 잠금 순서가 엇갈리지 않는다. 판단은 이벤트의 발생 시각만으로 하므로 현재 시각이나 전달 지연과 무관하게 같은 결과가 나온다.
 */
@Service
@RequiredArgsConstructor
public class JobApplicationAdmissionConsumptionService {

    private final JobApplicationAdmissionRepository admissionRepository;

    @Transactional
    public AdmissionConsumptionResult consume(AdmissionConsumptionCommand command) {
        if (command.admissionId() == null) {
            return AdmissionConsumptionResult.REJECTED_MISSING_ADMISSION_ID;
        }
        if (command.occurredAt() == null) {
            return AdmissionConsumptionResult.REJECTED_MISSING_OCCURRED_AT;
        }
        Optional<JobApplicationAdmission> locked = admissionRepository.findByIdForUpdate(command.admissionId());
        if (locked.isEmpty()) {
            return AdmissionConsumptionResult.REJECTED_ADMISSION_NOT_FOUND;
        }
        return consumeLocked(locked.get(), command);
    }

    private AdmissionConsumptionResult consumeLocked(
            JobApplicationAdmission admission,
            AdmissionConsumptionCommand command
    ) {
        // 상태보다 요청 내용을 먼저 확인한다. 다른 지원의 이벤트가 이미 사용된 승인의 재전송처럼 보이면 안 된다.
        if (!admission.getJobPostId().equals(command.jobPostId())) {
            return AdmissionConsumptionResult.REJECTED_JOB_POST_MISMATCH;
        }
        if (!admission.getWorkerMemberId().equals(command.workerMemberId())) {
            return AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH;
        }
        if (admission.isConsumed()) {
            return AdmissionConsumptionResult.ALREADY_CONSUMED;
        }
        LocalDateTime submittedAt = toStoredTime(command.occurredAt());
        if (!admission.isSubmittedBeforeExpiry(submittedAt)) {
            return AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY;
        }
        admission.consume(submittedAt);
        return AdmissionConsumptionResult.CONSUMED;
    }

    // 저장 정밀도(DATETIME(6))로 먼저 절삭한다. 나노초 값을 그대로 저장하면 MySQL 반올림으로 consumedAt이 만료 시각과 같아질 수 있다.
    // 저장된 만료 시각은 마이크로초 단위이므로 절삭해도 "만료 시각보다 앞선다"는 판정은 바뀌지 않는다.
    private LocalDateTime toStoredTime(LocalDateTime occurredAt) {
        return occurredAt.truncatedTo(ChronoUnit.MICROS);
    }
}

package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용이 확인되지 않은 채 만료 시각이 지난 지원 접수 승인 한 건을 EXPIRED로 바꾼다.
 *
 * <p>승인 행을 기본 키로 잠근 뒤 {@link Clock}에서 현재 시각을 읽어 상태와 만료 시각을 다시 판단한다. {@code ApplicationSubmitted} 소비와
 * 같은 승인 행 잠금을 쓰므로 둘은 커밋 순서로 직렬화된다. 소비가 먼저 커밋했으면 CONSUMED를 그대로 두고, 만료가 먼저 커밋했으면 만료 전에
 * 저장된 지원의 늦은 이벤트가 EXPIRED를 CONSUMED로 바꾼다. 공고 행은 잠그지 않으므로 승인 발급과 잠금 순서가 엇갈리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JobApplicationAdmissionExpiryService {

    private final JobApplicationAdmissionRepository admissionRepository;
    private final Clock clock;

    @Transactional
    public boolean expireIfOverdue(Long admissionId) {
        Optional<JobApplicationAdmission> locked = admissionRepository.findByIdForUpdate(admissionId);
        if (locked.isEmpty()) {
            return false;
        }
        JobApplicationAdmission admission = locked.get();
        LocalDateTime now = LocalDateTime.now(clock);
        if (!admission.isOverdueReservation(now)) {
            return false;
        }
        admission.expire(now);
        return true;
    }
}

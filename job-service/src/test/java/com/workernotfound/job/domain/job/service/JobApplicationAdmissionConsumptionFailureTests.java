package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 일시 오류는 처리 결과(정상·영구 거절)로 바꾸지 않고 그대로 전파해야 소비자가 같은 이벤트를 재시도할 수 있다.
class JobApplicationAdmissionConsumptionFailureTests {

    private final JobApplicationAdmissionRepository admissionRepository = mock(JobApplicationAdmissionRepository.class);
    private final JobApplicationAdmissionConsumptionService consumptionService =
            new JobApplicationAdmissionConsumptionService(admissionRepository);
    private final AdmissionConsumptionCommand command =
            new AdmissionConsumptionCommand(1L, 2L, 3L, LocalDateTime.of(2030, 1, 1, 12, 0));

    @Test
    void propagatesDatabaseFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("연결 실패");
        when(admissionRepository.findByIdForUpdate(1L)).thenThrow(failure);

        assertThatThrownBy(() -> consumptionService.consume(command)).isSameAs(failure);
    }

    @Test
    void propagatesLockAcquisitionFailure() {
        CannotAcquireLockException failure = new CannotAcquireLockException("잠금 대기 시간 초과");
        when(admissionRepository.findByIdForUpdate(1L)).thenThrow(failure);

        assertThatThrownBy(() -> consumptionService.consume(command)).isSameAs(failure);
    }
}

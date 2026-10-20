package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 만료 시각이 지난 RESERVED 지원 접수 승인을 찾아 승인마다 {@link JobApplicationAdmissionExpiryService}로 EXPIRED 처리한다.
 *
 * <p>범위 조건 UPDATE를 쓰지 않는다. 잠그지 않고 대상 ID만 고른 뒤 승인마다 별도 트랜잭션에서 기본 키로 잠그고 다시 판단한다. 보조 인덱스
 * 범위 갱신의 gap 잠금이 승인 발급 INSERT와 엇갈리지 않고, 한 트랜잭션이 여러 승인을 오래 잠그지 않는다. 반복·동시 실행에서도 잠금 뒤
 * 상태 조건으로 한 번만 바뀐다. 순회 시작 때 최대 ID를 고정하고 그 범위만 배치로 나눈다. 범위를 끝내면 다음 실행은 처음부터 찾는다. 처리에 실패한
 * 승인도 조회 위치를 넘기므로 계속 실패하는 승인이 매 배치를 차지하지 않는다. 실패한 승인은 RESERVED로 남아 조회 위치가 처음으로 돌아온 뒤
 * 다시 시도된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(JobApplicationAdmissionExpiryProperties.class)
public class JobApplicationAdmissionExpiryReconciler {

    private final JobApplicationAdmissionRepository admissionRepository;
    private final JobApplicationAdmissionExpiryService expiryService;
    private final JobApplicationAdmissionExpiryProperties properties;
    private final Clock clock;
    private long lastAdmissionId;
    private long upperAdmissionId;

    // 같은 인스턴스에서는 조회 위치와 상한을 함께 갱신한다. 다른 인스턴스와의 변경 경합은 승인 행 잠금이 처리한다.
    public synchronized int reconcile() {
        if (upperAdmissionId == 0) {
            upperAdmissionId = admissionRepository.findMaxId();
        }
        // 만료 시각은 DATETIME(6)이다. 인자를 절삭해야 MySQL이 나노초를 반올림해 경계를 앞당기지 않는다.
        // 후보 조회일 뿐 최종 판단은 잠금 뒤 절삭하지 않은 시각으로 한다.
        List<Long> overdueIds = admissionRepository.findOverdueReservedIdsAfter(
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS),
                lastAdmissionId,
                upperAdmissionId,
                PageRequest.of(0, properties.batchSize()));
        advanceCursor(overdueIds);
        int expired = 0;
        for (Long admissionId : overdueIds) {
            if (expireSafely(admissionId)) {
                expired++;
            }
        }
        return expired;
    }

    // 상한까지 보았거나 배치가 덜 차면 순회를 끝낸다. 처리 성공 여부와 신규 유입량에 관계없이 반드시 돌아온다.
    private void advanceCursor(List<Long> admissionIds) {
        long nextId = admissionIds.isEmpty() ? 0 : admissionIds.get(admissionIds.size() - 1);
        if (admissionIds.size() < properties.batchSize() || nextId >= upperAdmissionId) {
            lastAdmissionId = 0;
            upperAdmissionId = 0;
            return;
        }
        lastAdmissionId = nextId;
    }

    // 한 승인의 실패가 다른 승인의 만료를 막지 않게 한다. 실패한 승인은 조회 위치가 처음으로 돌아온 뒤 다시 찾는다.
    private boolean expireSafely(Long admissionId) {
        try {
            return expiryService.expireIfOverdue(admissionId);
        } catch (RuntimeException exception) {
            log.warn("지원 접수 승인 만료 처리 실패: admissionId={}, type={}",
                    admissionId, exception.getClass().getName());
            return false;
        }
    }
}

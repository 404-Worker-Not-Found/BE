package com.workernotfound.job.domain.job.controller;

import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.support.FundingStatusApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예치 차단(funded=false)이 신규 지원 승인·자리 예약·최초 확정만 막고, 이미 성공한 명령의 멱등 결과와 확정 자리는 유지하는지 검증한다.
 */
class FundingBlockRecruitmentApiTests extends FundingStatusApiTestSupport {

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Test
    void blocksNewRecruitmentButKeepsResultsOfEarlierCommands() throws Exception {
        LinkedJob job = createLinkedJob(3);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        String admissionKey = newKey("admission");
        String admission = requestAdmission(job.jobPostId(), admissionKey, 100L)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reservedKey = newKey("seat");
        String reserved = reservationId(reserveSeat(job.jobPostId(), reservedKey, 501L, 601L, 701L));
        String consumedKey = newKey("seat");
        String consumed = reservationId(reserveSeat(job.jobPostId(), consumedKey, 502L, 602L, 702L));
        String confirmKey = newKey("confirm");
        seatCommand(job.jobPostId(), consumed, "confirm", confirmKey).andExpect(status().isOk());
        assertThat(searchedJobIds()).contains(job.jobPostId());

        sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"))
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"));

        // 신규 지원 승인·자리 예약·아직 RESERVED인 자리의 최초 확정을 거절한다.
        requestAdmission(job.jobPostId(), newKey("admission"), 101L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-001"));
        reserveSeat(job.jobPostId(), newKey("seat"), 503L, 603L, 703L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-005"));
        seatCommand(job.jobPostId(), reserved, "confirm", newKey("confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-005"));
        assertThat(reservation(reserved).getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);

        // 이미 성공한 명령의 같은 키 재요청은 처음 결과를 그대로 받는다.
        String replayedAdmission = requestAdmission(job.jobPostId(), admissionKey, 100L)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(replayedAdmission, "$.data")).isEqualTo(JsonPath.read(admission, "$.data"));
        assertThat(reservationId(reserveSeat(job.jobPostId(), reservedKey, 501L, 601L, 701L))).isEqualTo(reserved);
        seatCommand(job.jobPostId(), consumed, "confirm", confirmKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONSUMED"));
        // 확정된 자리는 예치 차단으로 반환·취소하지 않는다. 아직 RESERVED인 자리는 Saga 보상으로 반환할 수 있다.
        assertThat(reservation(consumed).getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        seatCommand(job.jobPostId(), reserved, "release", newKey("release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));

        // 차단 공고는 검색에서 빠지지만 상세는 기존과 같이 공개된다. 공고 상태는 OPEN 그대로다.
        assertThat(searchedJobIds()).doesNotContain(job.jobPostId());
        mockMvc.perform(get("/api/jobs/{id}", job.jobPostId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(job.jobPostId()));
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
    }

    private JobMatchingSeatReservation reservation(String reservationId) {
        return reservationRepository.findById(Long.valueOf(reservationId)).orElseThrow();
    }

    // 검색은 메모리 페이지 처리라 다른 테스트의 공개 공고가 섞인다. 모든 페이지에서 ID를 모은다.
    private List<Long> searchedJobIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int page = 0; ; page++) {
            String body = mockMvc.perform(get("/api/jobs/search").param("page", String.valueOf(page)).param("size", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Number> pageIds = JsonPath.read(body, "$.data.jobs[*].id");
            if (pageIds.isEmpty()) {
                return ids;
            }
            pageIds.forEach(id -> ids.add(id.longValue()));
        }
    }
}

package com.workernotfound.job.domain.job.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@AutoConfigureMockMvc
class JobBusinessAdmissionTests extends IntegrationTestSupport {
    static final AtomicLong IDS = new AtomicLong(8_000_000);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProperties jwt;

    String body() {
        var date = TestSchedule.today().plusDays(2);
        return """
            {"businessId":777,"categoryId":1,"storeName":"사업장","address":"서울","title":"공고","description":"설명",
             "workDate":"%s","startTime":"09:00","endTime":"18:00","isEndTimeNextDay":false,
             "baseHourlyWage":10320,"recruitCount":1,"latitude":37.5,"longitude":127,"urgencyLevel":"LOW",
             "applicationDeadline":"%s"}
            """.formatted(date,date.minusDays(1).atTime(12,0));
    }
    String bearer(long owner) {return TestAccessTokens.bearer(TestAccessTokens.owner(jwt.secret(),owner));}

    @Test void ownershipUsesJwtIdentityBeforeEnteringWriteTransaction() throws Exception {
        long owner = IDS.incrementAndGet();
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;})
                .when(businessValidator).validateOwnership(777L,owner);
        mvc.perform(post("/api/jobs").header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());
        verify(businessValidator).validateOwnership(777L,owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posts WHERE owner_id=?",Long.class,owner)).isEqualTo(1);
    }
    @Test void wrongBusinessOrUnavailableLookupSavesNeitherJobNorPaymentCommand() throws Exception {
        for (var error : new JobErrorCode[]{JobErrorCode.BUSINESS_NOT_OWNED,JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE}) {
            long owner=IDS.incrementAndGet();
            doThrow(new BusinessException(error)).when(businessValidator).validateOwnership(777L,owner);
            mvc.perform(post("/api/jobs").header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().is(error.getHttpStatus().value())).andExpect(jsonPath("$.code").value(error.getCode()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posts WHERE owner_id=?",Long.class,owner)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_payment_order_commands WHERE owner_member_id=?",Long.class,owner)).isZero();
        }
    }
}

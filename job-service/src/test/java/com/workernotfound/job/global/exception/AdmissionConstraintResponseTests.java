package com.workernotfound.job.global.exception;

import com.workernotfound.job.domain.job.controller.JobInternalController;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import jakarta.persistence.RollbackException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdmissionConstraintResponseTests {

    private static final String KEY_CONSTRAINT = "uk_job_application_admissions_idempotency_key";

    private JobApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(JobApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new JobInternalController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        KEY_CONSTRAINT,
        "job_application_admissions." + KEY_CONSTRAINT
    })
    void mapsConstraintThroughTransactionAndPersistenceWrappers(String constraintName) throws Exception {
        requestWithFailure(wrappedViolation(constraintName))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"))
                .andExpect(content().string(not(containsString("sensitive"))));
    }

    @Test
    void mapsDirectHibernateConstraintViolation() throws Exception {
        requestWithFailure(violation(KEY_CONSTRAINT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
    }

    @Test
    void searchesPastAnUnrelatedConstraintViolation() throws Exception {
        SQLException cause = new SQLException("sensitive", violation(KEY_CONSTRAINT));
        ConstraintViolationException outer = new ConstraintViolationException("sensitive", cause, "other_key");

        requestWithFailure(new DataIntegrityViolationException("sensitive", outer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "fk_job_application_admissions_job_post",
        KEY_CONSTRAINT + "_other",
        "other_" + KEY_CONSTRAINT
    })
    void keepsUnknownOrDifferentConstraintsAsServerErrors(String constraintName) throws Exception {
        requestWithFailure(wrappedViolation(constraintName))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andExpect(content().string(not(containsString("sensitive"))));
    }

    @Test
    void doesNotMatchConstraintNameFromExceptionMessage() throws Exception {
        requestWithFailure(new DataIntegrityViolationException(KEY_CONSTRAINT, new SQLException(KEY_CONSTRAINT)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"));
    }

    @Test
    void handlerStopsAtACyclicCauseChainWithoutMatchingConstraint() {
        RuntimeException first = new RuntimeException("sensitive");
        RuntimeException second = new RuntimeException("sensitive", first);
        first.initCause(second);

        // Spring MVC의 예외 탐색 전에 발생하는 순환은 별개이므로 전역 처리기의 탐색 종료만 검증한다.
        var response = new GlobalExceptionHandler().handleException(first, new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    private TransactionSystemException wrappedViolation(String constraintName) {
        return new TransactionSystemException("sensitive",
                new RollbackException("sensitive",
                        new DataIntegrityViolationException("sensitive", violation(constraintName))));
    }

    private ConstraintViolationException violation(String constraintName) {
        return new ConstraintViolationException("sensitive", new SQLException("sensitive"), constraintName);
    }

    private ResultActions requestWithFailure(RuntimeException failure) throws Exception {
        when(service.createApplicationAdmission(1L, 100L, "test-key")).thenThrow(failure);
        return mvc.perform(post("/api/jobs/internal/1/application-admissions")
                .header("Idempotency-Key", "test-key")
                .contentType("application/json")
                .content("{\"workerMemberId\":100}"));
    }
}

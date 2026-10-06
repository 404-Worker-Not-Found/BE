package com.workernotfound.job.global.security;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.job.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class JwtFilterErrorTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @MockitoSpyBean JwtTokenParser parser;

    @Test
    void engineFailureUsesSafe500ThroughMvcResolver() throws Exception {
        doThrow(new JwtProcessingException(new IllegalStateException("sensitive engine detail")))
                .when(parser)
                .parseAccessToken("engine-failure");
        mvc.perform(get("/api/protected-boundary").header("Authorization", "Bearer engine-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    // 점주 본인 공고 목록도 공개 공고 조회 경로(GET /api/jobs/**)와 달리 같은 토큰 처리 경계를 따른다.
    @Test
    void engineFailureOnOwnerJobListUsesSafe500() throws Exception {
        doThrow(new JwtProcessingException(new IllegalStateException("sensitive engine detail")))
                .when(parser)
                .parseAccessToken("owner-list-engine-failure");
        mvc.perform(get("/api/jobs/me").header("Authorization", "Bearer owner-list-engine-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test
    void invalidTokenKeepsProtectedEndpointUnauthorized() throws Exception {
        mvc.perform(get("/api/protected-boundary").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    }
}

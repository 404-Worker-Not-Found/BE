package com.workernotfound.job.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ErrorResponseTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new Endpoints())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void internalArgumentErrorsAre500WithoutDetails() throws Exception {
        mvc.perform(get("/test/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test
    void missingHeaderAndMalformedJsonAreSafe400() throws Exception {
        mvc.perform(post("/test/input").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        mvc.perform(
                        post("/test/input")
                                .header("X-Test", "present")
                                .contentType("application/json")
                                .content("sensitive invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test
    void methodAndContentTypeKeepStandardStatuses() throws Exception {
        mvc.perform(get("/test/input"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("GLOBAL-405-001"));
        mvc.perform(
                        post("/test/input")
                                .header("X-Test", "present")
                                .contentType("text/plain")
                                .content("bad"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("GLOBAL-415-001"));
    }

    @Test
    void missingResourceAndUnacceptableRepresentationKeepProtocolCodes() throws Exception {
        mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GLOBAL-404-001"));
        mvc.perform(
                        post("/test/input")
                                .header("X-Test", "present")
                                .contentType("application/json")
                                .accept("application/xml")
                                .content("{}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("GLOBAL-406-001"));
    }

    // 요청 파라미터 바인딩의 형 변환 실패는 내부 타입 이름과 입력 원문 대신 고정 문구로 응답한다.
    @Test
    void bindingConversionFailureUsesSafeReason() throws Exception {
        mvc.perform(get("/test/binding").param("count", "sensitive-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-002"))
                .andExpect(jsonPath("$.reasons.count").value("유효하지 않은 값입니다."))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive")),
                                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("java.lang")))));
    }

    record BindingInput(Integer count) {}

    @RestController
    static class Endpoints {
        @GetMapping("/test/binding")
        Object binding(@jakarta.validation.Valid @ModelAttribute BindingInput input) {
            return input;
        }

        @GetMapping("/test/bug")
        String bug() {
            throw new IllegalArgumentException("sensitive details");
        }

        @PostMapping("/test/input")
        Object input(
                @RequestHeader("X-Test") String header, @RequestBody java.util.Map<String, Object> body) {
            return body;
        }
    }
}

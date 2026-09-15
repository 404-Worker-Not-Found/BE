package com.workernotfound.chat.global.exception;

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

  @RestController
  static class Endpoints {
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

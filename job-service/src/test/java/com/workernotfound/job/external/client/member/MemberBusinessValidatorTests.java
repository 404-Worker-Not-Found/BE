package com.workernotfound.job.external.client.member;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MemberBusinessValidatorTests {
    HttpServer server;
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> body = new AtomicReference<>("{\"success\":true,\"data\":{\"businessId\":7,\"ownerMemberId\":42}}");
    final AtomicReference<String> path = new AtomicReference<>(), secret = new AtomicReference<>();
    final CountDownLatch releaseBody = new CountDownLatch(1);
    volatile boolean stallBody;
    MemberBusinessValidator client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());secret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try {
                if (stallBody) { exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();releaseBody.await(3,TimeUnit.SECONDS); }
                else exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ignored) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start();
        client = new MemberBusinessValidator(new MemberServiceProperties("http://127.0.0.1:"+server.getAddress().getPort(),
                Duration.ofMillis(100),Duration.ofMillis(300)),new InternalApiProperties("private-test-secret"),new ObjectMapper());
    }
    @AfterEach void stop() {releaseBody.countDown();server.stop(0);}
    void rejected(JobErrorCode expected) {
        assertThatThrownBy(()->client.validateOwnership(7L,42L)).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(expected);
    }
    @Test void matchesRegisteredBusinessAndSendsOnlyTrustedOwnerAndSecret() {
        client.validateOwnership(7L,42L);
        assertThat(path.get()).isEqualTo("/api/members/internal/owners/42/business");
        assertThat(secret.get()).isEqualTo("private-test-secret");
    }
    @Test void rejectsOtherBusinessEvenWhenMemberResponseIsValid() {rejectedAfterBody("{\"success\":true,\"data\":{\"businessId\":8,\"ownerMemberId\":42}}",JobErrorCode.BUSINESS_NOT_OWNED);}
    @ParameterizedTest @CsvSource({"404,MEMBER-404-002,deny","404,OWNER-404-001,deny","404,UNKNOWN,unavailable","503,MEMBER-404-002,unavailable","401,OWNER-404-001,unavailable"})
    void onlyKnownStatusAndCodePairsAreBusinessRejections(int http,String code,String result) {
        status.set(http);body.set("{\"success\":false,\"code\":\""+code+"\"}");
        rejected(result.equals("deny")?JobErrorCode.BUSINESS_NOT_OWNED:JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);
    }
    @Test void rejectsMalformedWrongOwnerAndNonIntegralIdentifiers() {
        for (String invalid : new String[]{"not-json", "{}", "null", "{\"success\":true,\"data\":{\"businessId\":7,\"ownerMemberId\":43}}",
                "{\"success\":true,\"data\":{\"businessId\":7.0,\"ownerMemberId\":42}}", "{\"success\":true,\"data\":{\"businessId\":7,\"ownerMemberId\":42}} {}"}) {
            rejectedAfterBody(invalid,JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);
        }
    }
    @Test void oversizedBodyFailsClosed() {rejectedAfterBody(" ".repeat(5000),JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);}
    @Test void bodyStallIsBoundedEvenAfterSuccessfulHeaders() {
        stallBody=true;long start=System.nanoTime();rejected(JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void stoppedServerFailsClosed() {server.stop(0);rejected(JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);}
    void rejectedAfterBody(String response,JobErrorCode expected) {body.set(response);rejected(expected);}
}

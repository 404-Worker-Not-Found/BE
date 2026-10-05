package com.workernotfound.payment.external;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.global.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossClientTests {
  @Test
  void sendsServerAmountAndStableIdempotencyKeyAndParsesOnlyNeededFields() {
    var props = new TossProperties(true, "test_sk_fixture", null);
    var builder =
        RestClient.builder()
            .baseUrl("https://api.tosspayments.com")
            .defaultHeaders(h -> h.setBasicAuth(props.secretKey(), ""));
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "confirm-order-1"))
        .andExpect(
            header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("test_sk_fixture:".getBytes())))
        .andExpect(
            content().json("{\"orderId\":\"order-1\",\"paymentKey\":\"key-1\",\"amount\":10000}"))
        .andRespond(
            withSuccess(
                """
                {"orderId":"order-1","paymentKey":"key-1","status":"DONE","method":"카드","currency":"KRW",
                "totalAmount":10000,"balanceAmount":10000,"approvedAt":"2026-09-22T01:00:00+09:00","extra":"ignored"}
                """,
                MediaType.APPLICATION_JSON));
    var client = new TossClient(builder.build(), props);
    assertThat(client.confirm("order-1", "key-1", new BigDecimal("10000")).status())
        .isEqualTo("DONE");
    server.verify();
  }

  @Test
  void providerErrorsRemainUnknownAndDoNotExposeRemoteBody() {
    var builder = RestClient.builder().baseUrl("https://api.tosspayments.com");
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.tosspayments.com/v1/payments/key-1"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("sensitive provider details"));
    var client = new TossClient(builder.build(), new TossProperties(true, "test_sk_fixture", null));
    assertThatThrownBy(() -> client.getPayment("key-1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageNotContaining("sensitive");
    server.verify();
  }

  @Test
  void disabledIntegrationMakesNoRequestsAndLiveKeysAreRejected() {
    assertThatThrownBy(() -> new TossProperties(true, "live_sk_not_allowed", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TossProperties(true, "", null))
        .isInstanceOf(IllegalArgumentException.class);
    var client = new TossClient(RestClient.create(), new TossProperties(false, "", null));
    assertThatThrownBy(() -> client.confirm("order-1", "key", BigDecimal.ONE))
        .isInstanceOf(BusinessException.class);
    assertThat(new TossProperties(true, "test_sk_fixture", null).toString())
        .doesNotContain("test_sk_fixture");
  }

  @Test
  void apiBaseUrlDefaultsToTossAndAllowsOnlyLoopbackDoubles() {
    assertThat(new TossProperties(false, "", null).apiBaseUrl())
        .isEqualTo(TossProperties.DEFAULT_API_BASE_URL);
    assertThat(new TossProperties(true, "test_sk_fixture", "http://127.0.0.1:18080").apiBaseUrl())
        .isEqualTo("http://127.0.0.1:18080");
    // 시크릿 키를 Basic 인증으로 보내므로 다른 원격 주소는 HTTPS여도 거절한다.
    for (String url :
        new String[] {"https://example.com", "http://10.0.0.5:8080", "ftp://localhost", "not a url"}) {
      assertThatThrownBy(() -> new TossProperties(true, "test_sk_fixture", url))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("test_sk_fixture");
    }
  }
}

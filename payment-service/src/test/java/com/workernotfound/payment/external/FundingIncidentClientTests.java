package com.workernotfound.payment.external;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.workernotfound.payment.domain.funding.FundingIncidentRepository.Delivery;
import com.workernotfound.payment.external.funding.FundingIncidentClient;
import com.workernotfound.payment.global.security.InternalApiProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FundingIncidentClientTests {
  @Test void dedicatedClientTargetsNotificationServiceAndSendsStableKeyAndSecret() throws Exception {
    HttpServer job=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    HttpServer notification=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    AtomicInteger wrong=new AtomicInteger(),delivered=new AtomicInteger();
    job.createContext("/",e->{wrong.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});
    notification.createContext("/api/notifications/internal/funding-incidents",e->{
      assertThat(e.getRequestHeaders().getFirst("X-Internal-Secret")).isEqualTo("test-internal");
      assertThat(e.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("funding-incident-123");
      String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
      assertThat(body).contains("\"memberRole\":\"OWNER\"");
      delivered.incrementAndGet();byte[] response="{\"success\":true,\"status\":200}".getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);
      e.getResponseBody().write(response);e.close();
    });job.start();notification.start();
    try {
      new ApplicationContextRunner().withUserConfiguration(PaymentClientConfig.class)
          .withBean(InternalApiProperties.class,()->new InternalApiProperties("test-internal"))
          .withPropertyValues("payment.job-service.base-url=http://127.0.0.1:"+job.getAddress().getPort(),
              "payment.notification-service.base-url=http://127.0.0.1:"+notification.getAddress().getPort(),
              "payment.toss.api-base-url=https://api.tosspayments.com","payment.toss.enabled=false")
          .run(context->{
            assertThat(context).hasNotFailed();
            var client=new FundingIncidentClient(context.getBean("fundingIncidentRestClient",org.springframework.web.client.RestClient.class));
            client.deliver(new Delivery(123,"funding-incident-123",400L,20L,"OWNER",null,null,LocalDateTime.of(2026,10,28,9,0),"token"));
          });
      assertThat(delivered).hasValue(1);assertThat(wrong).hasValue(0);
    } finally {job.stop(0);notification.stop(0);}
  }
}

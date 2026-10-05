package com.workernotfound.auth.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.workernotfound.auth.external.client.member.MemberServiceProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RestClientConfigTests {

	@Test
	void memberServiceClientSendsPatchOverHttp() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/members/internal/1/contact", exchange -> {
			assertThat(exchange.getRequestMethod()).isEqualTo("PATCH");
			assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Secret"))
				.isEqualTo("test-internal-secret");
			byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			var properties = new MemberServiceProperties(
				"http://127.0.0.1:" + server.getAddress().getPort(),
				Duration.ofSeconds(2), Duration.ofSeconds(5), "test-internal-secret");
			String body = new RestClientConfig().memberServiceRestClient(properties)
				.patch().uri("/api/members/internal/1/contact")
				.body("{}")
				.retrieve().body(String.class);
			assertThat(body).isEqualTo("ok");
		} finally {
			server.stop(0);
		}
	}
}

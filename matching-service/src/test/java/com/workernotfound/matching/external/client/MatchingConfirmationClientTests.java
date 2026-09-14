package com.workernotfound.matching.external.client;

import com.workernotfound.matching.external.client.confirmation.MatchingConfirmationClient;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockRequest;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationRequest;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MatchingConfirmationClientTests {

	@Test
	void reservesSeatWithInternalSecretAndIdempotencyKey() {
		RestClient.Builder jobBuilder = internalBuilder("http://job-service");
		MockRestServiceServer jobServer = MockRestServiceServer.bindTo(jobBuilder).build();
		MatchingConfirmationClient client = client(jobBuilder, RestClient.builder(), RestClient.builder(), RestClient.builder());
		jobServer.expect(requestTo("http://job-service/api/jobs/internal/10/matching-seat-reservations"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-Internal-Secret", "test-secret"))
			.andExpect(header("Idempotency-Key", "seat-command"))
			.andRespond(withSuccess(
				"{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"message\":\"ok\","
					+ "\"data\":{\"reservationId\":\"seat-1\",\"jobPostId\":10,\"ownerMemberId\":100,"
					+ "\"workDate\":\"2026-09-20\",\"startTime\":\"09:00:00\",\"endTime\":\"18:00:00\","
					+ "\"lockedAmount\":120000.00,\"currency\":\"KRW\","
					+ "\"reservedAt\":\"2026-09-14T10:00:00\",\"expiresAt\":\"2026-09-14T10:05:00\"}}",
				MediaType.APPLICATION_JSON
			));

		SeatReservationResponse response = client.reserveSeat(
			10L,
			new SeatReservationRequest(1L, 2L, 3L),
			"seat-command"
		);

		assertThat(response.reservationId()).isEqualTo("seat-1");
		jobServer.verify();
	}

	@Test
	void locksAndReleasesPaymentWithSameStableCommandId() {
		RestClient.Builder paymentBuilder = internalBuilder("http://payment-service");
		MockRestServiceServer paymentServer = MockRestServiceServer.bindTo(paymentBuilder).build();
		MatchingConfirmationClient client = client(
			RestClient.builder(), paymentBuilder, RestClient.builder(), RestClient.builder()
		);
		paymentServer.expect(requestTo("http://payment-service/api/payments/internal/locks"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", "payment-command"))
			.andRespond(withSuccess(
				"{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"message\":\"ok\","
					+ "\"data\":{\"paymentId\":\"payment-1\"}}",
				MediaType.APPLICATION_JSON
			));
		paymentServer.expect(requestTo("http://payment-service/api/payments/internal/locks/payment-1/release"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", "payment-command"))
			.andRespond(withSuccess());

		String paymentId = client.lockPayment(new PaymentLockRequest(
			1L, 10L, 100L, 20L, new BigDecimal("120000.00"), "KRW"
		), "payment-command").paymentId();
		client.releasePayment(paymentId, "payment-command");

		paymentServer.verify();
	}

	private RestClient.Builder internalBuilder(String baseUrl) {
		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader("X-Internal-Secret", "test-secret");
	}

	private MatchingConfirmationClient client(
		RestClient.Builder job,
		RestClient.Builder payment,
		RestClient.Builder work,
		RestClient.Builder chat
	) {
		return new MatchingConfirmationClient(job.build(), payment.build(), work.build(), chat.build());
	}
}

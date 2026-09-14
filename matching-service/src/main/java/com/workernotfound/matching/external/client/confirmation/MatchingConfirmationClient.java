package com.workernotfound.matching.external.client.confirmation;

import com.workernotfound.matching.external.client.confirmation.dto.ChatRoomRequest;
import com.workernotfound.matching.external.client.confirmation.dto.ChatRoomResponse;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockRequest;
import com.workernotfound.matching.external.client.confirmation.dto.PaymentLockResponse;
import com.workernotfound.matching.external.client.confirmation.dto.ScheduledWorkRequest;
import com.workernotfound.matching.external.client.confirmation.dto.ScheduledWorkResponse;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationRequest;
import com.workernotfound.matching.external.client.confirmation.dto.SeatReservationResponse;
import com.workernotfound.matching.global.response.ApiResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MatchingConfirmationClient {

	private static final ParameterizedTypeReference<ApiResponse<SeatReservationResponse>> SEAT_RESPONSE =
		new ParameterizedTypeReference<>() {
		};
	private static final ParameterizedTypeReference<ApiResponse<PaymentLockResponse>> PAYMENT_RESPONSE =
		new ParameterizedTypeReference<>() {
		};
	private static final ParameterizedTypeReference<ApiResponse<ScheduledWorkResponse>> WORK_RESPONSE =
		new ParameterizedTypeReference<>() {
		};
	private static final ParameterizedTypeReference<ApiResponse<ChatRoomResponse>> CHAT_RESPONSE =
		new ParameterizedTypeReference<>() {
		};

	private final RestClient jobServiceRestClient;
	private final RestClient paymentServiceRestClient;
	private final RestClient workServiceRestClient;
	private final RestClient chatServiceRestClient;

	public MatchingConfirmationClient(
		@Qualifier("jobServiceRestClient") RestClient jobServiceRestClient,
		@Qualifier("paymentServiceRestClient") RestClient paymentServiceRestClient,
		@Qualifier("workServiceRestClient") RestClient workServiceRestClient,
		@Qualifier("chatServiceRestClient") RestClient chatServiceRestClient
	) {
		this.jobServiceRestClient = jobServiceRestClient;
		this.paymentServiceRestClient = paymentServiceRestClient;
		this.workServiceRestClient = workServiceRestClient;
		this.chatServiceRestClient = chatServiceRestClient;
	}

	public SeatReservationResponse reserveSeat(
		Long jobPostId,
		SeatReservationRequest request,
		String commandId
	) {
		try {
			ApiResponse<SeatReservationResponse> response = jobServiceRestClient.post()
				.uri("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPostId)
				.header("Idempotency-Key", commandId)
				.body(request)
				.retrieve()
				.body(SEAT_RESPONSE);
			return requireData(response, ConfirmationStep.SEAT_RESERVATION);
		} catch (RestClientResponseException exception) {
			throw responseException(ConfirmationStep.SEAT_RESERVATION, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(ConfirmationStep.SEAT_RESERVATION, exception);
		}
	}

	public PaymentLockResponse lockPayment(PaymentLockRequest request, String commandId) {
		try {
			ApiResponse<PaymentLockResponse> response = paymentServiceRestClient.post()
				.uri("/api/payments/internal/locks")
				.header("Idempotency-Key", commandId)
				.body(request)
				.retrieve()
				.body(PAYMENT_RESPONSE);
			return requireData(response, ConfirmationStep.PAYMENT_LOCK);
		} catch (RestClientResponseException exception) {
			throw responseException(ConfirmationStep.PAYMENT_LOCK, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(ConfirmationStep.PAYMENT_LOCK, exception);
		}
	}

	public ScheduledWorkResponse createScheduledWork(ScheduledWorkRequest request, String commandId) {
		try {
			ApiResponse<ScheduledWorkResponse> response = workServiceRestClient.post()
				.uri("/api/works/internal/scheduled")
				.header("Idempotency-Key", commandId)
				.body(request)
				.retrieve()
				.body(WORK_RESPONSE);
			return requireData(response, ConfirmationStep.WORK_CREATION);
		} catch (RestClientResponseException exception) {
			throw responseException(ConfirmationStep.WORK_CREATION, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(ConfirmationStep.WORK_CREATION, exception);
		}
	}

	public ChatRoomResponse createChatRoom(ChatRoomRequest request, String commandId) {
		try {
			ApiResponse<ChatRoomResponse> response = chatServiceRestClient.post()
				.uri("/api/chat-rooms/internal")
				.header("Idempotency-Key", commandId)
				.body(request)
				.retrieve()
				.body(CHAT_RESPONSE);
			return requireData(response, ConfirmationStep.CHAT_CREATION);
		} catch (RestClientResponseException exception) {
			throw responseException(ConfirmationStep.CHAT_CREATION, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(ConfirmationStep.CHAT_CREATION, exception);
		}
	}

	public void confirmSeat(Long jobPostId, String reservationId, String commandId) {
		postWithoutResponse(
			jobServiceRestClient,
			"/api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/confirm",
			jobPostId,
			reservationId,
			commandId,
			ConfirmationStep.SEAT_CONFIRMATION
		);
	}

	public void closeChatRoom(String chatRoomId, String commandId) {
		postWithoutResponse(
			chatServiceRestClient,
			"/api/chat-rooms/internal/{resourceId}/close",
			chatRoomId,
			commandId,
			ConfirmationStep.CHAT_COMPENSATION
		);
	}

	public void cancelScheduledWork(String workId, String commandId) {
		postWithoutResponse(
			workServiceRestClient,
			"/api/works/internal/{resourceId}/cancel",
			workId,
			commandId,
			ConfirmationStep.WORK_COMPENSATION
		);
	}

	public void releasePayment(String paymentId, String commandId) {
		postWithoutResponse(
			paymentServiceRestClient,
			"/api/payments/internal/locks/{resourceId}/release",
			paymentId,
			commandId,
			ConfirmationStep.PAYMENT_COMPENSATION
		);
	}

	public void releaseSeat(Long jobPostId, String reservationId, String commandId) {
		postWithoutResponse(
			jobServiceRestClient,
			"/api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/release",
			jobPostId,
			reservationId,
			commandId,
			ConfirmationStep.SEAT_COMPENSATION
		);
	}

	private <T> T requireData(ApiResponse<T> response, ConfirmationStep step) {
		if (response == null || !response.success() || response.data() == null) {
			throw new MatchingConfirmationClientException(step, "매칭 확정 외부 응답이 올바르지 않습니다: " + step);
		}
		return response.data();
	}

	private void postWithoutResponse(
		RestClient client,
		String path,
		Object resourceId,
		String commandId,
		ConfirmationStep step
	) {
		try {
			client.post()
				.uri(path, resourceId)
				.header("Idempotency-Key", commandId)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientResponseException exception) {
			throw responseException(step, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(step, exception);
		}
	}

	private void postWithoutResponse(
		RestClient client,
		String path,
		Long jobPostId,
		String reservationId,
		String commandId,
		ConfirmationStep step
	) {
		try {
			client.post()
				.uri(path, jobPostId, reservationId)
				.header("Idempotency-Key", commandId)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientResponseException exception) {
			throw responseException(step, exception);
		} catch (RestClientException exception) {
			throw new MatchingConfirmationClientException(step, exception);
		}
	}

	private MatchingConfirmationClientException responseException(
		ConfirmationStep step,
		RestClientResponseException exception
	) {
		return new MatchingConfirmationClientException(step, exception.getStatusCode(), exception);
	}
}

package com.workernotfound.auth.external.client.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.auth.external.client.member.MemberServiceClientException.FailureType;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest;
import com.workernotfound.auth.global.response.ApiResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberServiceClient {

	private static final String CREATE_OWNER_PATH = "/api/members/internal/owners";
	private static final String CREATE_WORKER_PATH = "/api/members/internal/workers";
	private static final String COMPENSATION_PATH = "/api/members/internal/{memberId}";
	private static final int MAX_ERROR_BODY_BYTES = 8192;
	private static final Set<String> SAFE_FIELDS = Set.of("name", "email", "phoneNumber",
		"desiredHourlyWage", "activityRadiusKm", "immediatelyAvailable", "preferredBusinessTypes",
		"availableTimes", "baseLocation", "businessRegistrationNumber", "storeName", "businessType", "storeLocation");
	private static final ParameterizedTypeReference<ApiResponse<CreateMemberResponse>> CREATE_MEMBER_RESPONSE_TYPE =
		new ParameterizedTypeReference<>() { };

	private final RestClient memberServiceRestClient;
	private final ObjectMapper objectMapper;

	public CreateMemberResponse createOwner(CreateOwnerMemberRequest request) {
		return execute(HttpMethod.POST, CREATE_OWNER_PATH, request);
	}

	public CreateMemberResponse createWorker(CreateWorkerMemberRequest request) {
		return execute(HttpMethod.POST, CREATE_WORKER_PATH, request);
	}

	public void deleteMemberForSignupCompensation(Long memberId) {
		execute(HttpMethod.DELETE, COMPENSATION_PATH, null, memberId);
	}

	private CreateMemberResponse execute(HttpMethod method, String path, Object body, Object... uriVariables) {
		long started = System.nanoTime();
		try {
			var request = memberServiceRestClient.method(method).uri(path, uriVariables);
			if (body != null) request.body(body);
			return request.exchange((httpRequest, response) -> readResponse(response, method));
		} catch (MemberServiceClientException exception) {
			logFailure(method, path, started, exception);
			throw exception;
		} catch (RestClientException exception) {
			MemberServiceClientException failure = failure(null, transportFailure(exception), exception);
			logFailure(method, path, started, failure);
			throw failure;
		}
	}

	private CreateMemberResponse readResponse(ConvertibleClientHttpResponse response, HttpMethod method) throws IOException {
		HttpStatusCode status = response.getStatusCode();
		try {
			if (status.isError()) throw readError(response, status, method);
			if (!status.is2xxSuccessful()) throw failure(status, FailureType.INVALID_RESPONSE, null);
			if (method == HttpMethod.DELETE) return null;
			ApiResponse<CreateMemberResponse> body = response.bodyTo(CREATE_MEMBER_RESPONSE_TYPE);
			if (body == null || !body.success() || body.data() == null || body.data().memberId() == null) {
				throw failure(status, FailureType.INVALID_RESPONSE, null);
			}
			return body.data();
		} catch (IOException exception) {
			throw failure(status, transportFailure(exception), exception);
		} catch (RestClientException exception) {
			throw failure(status, responseFailure(exception), exception);
		}
	}

	private MemberServiceClientException readError(ConvertibleClientHttpResponse response,
			HttpStatusCode status, HttpMethod method) throws IOException {
		byte[] bytes = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES + 1);
		if (bytes.length > MAX_ERROR_BODY_BYTES) return failure(status, FailureType.HTTP_ERROR, null);
		try {
			JsonNode body = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
			String code = body == null ? null : safeCode(body.path("code").textValue());
			MemberServiceErrorCode error = MemberServiceErrorCode.find(status, code);
			// 보상 삭제의 계약 오류는 가입 입력 오류로 공개하지 않는다.
			if (method != HttpMethod.POST && (error == MemberServiceErrorCode.INVALID_REQUEST
					|| error == MemberServiceErrorCode.VALIDATION_ERROR)) error = null;
			Map<String, Object> reasons = error == MemberServiceErrorCode.VALIDATION_ERROR
				? safeReasons(body.path("reasons")) : null;
			return new MemberServiceClientException(status, error, code, reasons, FailureType.HTTP_ERROR, null);
		} catch (JsonProcessingException exception) {
			return failure(status, FailureType.HTTP_ERROR, null);
		}
	}

	private String safeCode(String code) {
		return code != null && code.matches("(?:GLOBAL|MEMBER|OWNER|WORKER)-[0-9]{3}-[0-9]{3}") ? code : null;
	}

	private Map<String, Object> safeReasons(JsonNode reasons) {
		if (!reasons.isObject()) return null;
		Map<String, Object> safe = new LinkedHashMap<>();
		reasons.fieldNames().forEachRemaining(field -> {
			if (isSafeField(field)) safe.put(field, "유효하지 않은 값입니다.");
		});
		return safe;
	}

	private boolean isSafeField(String field) {
		return SAFE_FIELDS.contains(field)
			|| field.matches("(?:baseLocation|storeLocation)\\.(?:address|detailAddress|latitude|longitude)")
			|| field.matches("availableTimes\\[(?:[0-9]|[1-4][0-9])\\]\\.(?:dayOfWeek|startTime|endTime)")
			|| field.matches("preferredBusinessTypes\\[(?:[0-9]|1[0-9])\\](?:\\.<list element>)?");
	}

	private FailureType transportFailure(Throwable exception) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return FailureType.TIMEOUT;
			if (cause instanceof ConnectException) return FailureType.CONNECTION_FAILURE;
		}
		return FailureType.TRANSPORT_FAILURE;
	}

	private FailureType responseFailure(RestClientException exception) {
		FailureType transport = transportFailure(exception);
		if (transport != FailureType.TRANSPORT_FAILURE || exception instanceof ResourceAccessException) return transport;
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
			if (cause instanceof IOException && !(cause instanceof JsonProcessingException)) return transport;
		}
		return FailureType.RESPONSE_DECODING;
	}

	private MemberServiceClientException failure(HttpStatusCode status, FailureType type, Throwable cause) {
		return new MemberServiceClientException(status, null, null, null, type, cause);
	}

	private void logFailure(HttpMethod method, String path, long started, MemberServiceClientException failure) {
		log.warn("내부 API 호출 실패: service=member-service method={} path={} status={} code={} failure={} elapsedMs={}",
			method, path, failure.getStatusCode() == null ? "NONE" : failure.getStatusCode().value(),
			failure.getRemoteCode() == null ? "UNRECOGNIZED" : failure.getRemoteCode(), failure.getFailureType(),
			(System.nanoTime() - started) / 1_000_000);
	}
}

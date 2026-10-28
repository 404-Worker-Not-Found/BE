package com.workernotfound.member.domain.review.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * 형 변환 없이 정수 JSON 숫자만 받는다. 전역 설정은 소수를 정수 필드로 잘라 받으므로({@code 4.5 -> 4}) 평점에만 적용한다.
 * null은 호출되지 않고 Bean Validation이 거절한다. job-service의 같은 역직렬화기와 같은 방식이다.
 */
final class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT || parser.getNumberType() != JsonParser.NumberType.INT)
			throw MismatchedInputException.from(parser, Integer.class, "정수 JSON 숫자가 필요합니다.");
		return parser.getIntValue();
	}
}

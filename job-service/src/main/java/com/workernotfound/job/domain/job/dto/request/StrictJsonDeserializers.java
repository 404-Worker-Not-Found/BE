package com.workernotfound.job.domain.job.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * 형 변환 없이 JSON 원래 형식만 받는 역직렬화기. 전역 설정은 소수를 정수 필드로 잘라 받으므로({@code 1.5 -> 1}) 버전·revision처럼
 * 비교 기준이 되는 값이 다른 값으로 바뀌지 않게 필드에만 적용한다. null은 호출되지 않고 Bean Validation이 거절한다.
 */
final class StrictJsonDeserializers {

    private StrictJsonDeserializers() {
    }

    static final class IntegerLong extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw MismatchedInputException.from(parser, Long.class, "정수 JSON 숫자가 필요합니다.");
            }
            return parser.getLongValue();
        }
    }

    static final class BooleanValue extends ValueDeserializer<Boolean> {

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            JsonToken token = parser.currentToken();
            if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                throw MismatchedInputException.from(parser, Boolean.class, "JSON 불리언이 필요합니다.");
            }
            return token == JsonToken.VALUE_TRUE;
        }
    }
}

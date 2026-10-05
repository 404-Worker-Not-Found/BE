package com.workernotfound.job.domain.job.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 예외와 그 cause 사슬의 클래스 이름만 이어 붙인다. 예: {@code java.lang.IllegalStateException <- java.io.IOException}
 *
 * <p>예외 메시지, suppressed 예외, 스택 트레이스에는 요청 헤더 값이나 상대 응답 같은 검증되지 않은 문자열이 담길 수 있다.
 * 예상하지 못한 예외를 기록하는 실행 경계에서는 원본 예외 대신 이 요약만 남긴다.
 */
final class ExceptionTypeChain {

    private static final int MAX_DEPTH = 8;

    private ExceptionTypeChain() {
    }

    static String describe(Throwable exception) {
        StringJoiner types = new StringJoiner(" <- ");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_DEPTH && visited.add(current); depth++) {
            types.add(current.getClass().getName());
            current = current.getCause();
        }
        return types.toString();
    }
}

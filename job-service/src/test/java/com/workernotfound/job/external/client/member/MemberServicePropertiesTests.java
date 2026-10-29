package com.workernotfound.job.external.client.member;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemberServicePropertiesTests {
    @Test void acceptsOnlyHttpsOrLoopbackWithoutCredentials() {
        for(String url:new String[]{"https://member.example","http://localhost:8082","http://127.0.0.1:8082"}) {
            assertThatCode(()->new MemberServiceProperties(url,Duration.ofSeconds(1),Duration.ofSeconds(2))).doesNotThrowAnyException();
        }
        for(String url:new String[]{"http://member.example","https://user:password@member.example","https:/broken","","https://member.example?token=private"}) {
            assertThatThrownBy(()->new MemberServiceProperties(url,Duration.ofSeconds(1),Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsUnboundedOrInconsistentTimeouts() {
        for(Duration duration:new Duration[]{Duration.ZERO,Duration.ofNanos(1),Duration.ofMinutes(2)}) {
            assertThatThrownBy(()->new MemberServiceProperties("http://localhost",Duration.ofMillis(1),duration)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new MemberServiceProperties("http://localhost",Duration.ofSeconds(3),Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.workernotfound.job.external.client.matching;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 응답 본문을 최대 크기까지만 모은다. 한도에 도달하면 구독을 취소하고 그때까지 받은 앞부분으로 완료한다.
 *
 * <p>본문 수신 완료까지가 HTTP 교환의 일부이므로, 전체 호출 제한시간이 지나 교환을 취소하면 이 수신도 함께 멈춘다.
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maxBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) {
            return;
        }
        for (ByteBuffer item : items) {
            int length = Math.min(item.remaining(), maxBytes - buffer.size());
            byte[] chunk = new byte[length];
            item.get(chunk);
            buffer.write(chunk, 0, length);
            if (buffer.size() >= maxBytes) {
                subscription.cancel();
                body.complete(buffer.toByteArray());
                return;
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        body.complete(buffer.toByteArray());
    }
}

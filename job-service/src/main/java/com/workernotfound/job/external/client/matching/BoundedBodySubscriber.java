package com.workernotfound.job.external.client.matching;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 응답 본문을 최대 크기까지만 모은다. 한도에 도달하면 구독을 취소하고 잘렸다는 표시와 함께 완료한다.
 *
 * <p>본문 수신 중 전송 오류는 {@link #onError}로 전달되어 교환 자체가 실패한다. 즉 이 구독자가 정상 완료한 본문은
 * 끝까지 받은 본문이거나 한도에서 잘린 본문뿐이며, 읽다가 끊긴 본문은 응답으로 만들어지지 않는다.
 * 본문 수신까지가 HTTP 교환의 일부이므로, 전체 호출 제한시간이 지나 교환을 취소하면 이 수신도 함께 멈춘다.
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<BoundedBodySubscriber.ReceivedBody> {

    private final int maxBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<ReceivedBody> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<ReceivedBody> getBody() {
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
            if (buffer.size() + item.remaining() > maxBytes) {
                subscription.cancel();
                body.complete(new ReceivedBody(new byte[0], true));
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            buffer.write(chunk, 0, chunk.length);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        body.complete(new ReceivedBody(buffer.toByteArray(), false));
    }

    /**
     * 정상 종료된 본문 수신 결과.
     *
     * @param bytes       끝까지 받은 본문. 잘린 경우 비어 있다.
     * @param isTruncated 한도를 넘어 수신을 멈췄으면 true. 불완전한 본문이므로 성공 응답으로 인정하지 않는다.
     */
    record ReceivedBody(byte[] bytes, boolean isTruncated) {
    }
}

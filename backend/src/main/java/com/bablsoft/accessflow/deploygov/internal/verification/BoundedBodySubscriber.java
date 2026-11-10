package com.bablsoft.accessflow.deploygov.internal.verification;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Buffers at most {@code capacity} bytes of a response body and cancels the upstream once more
 * arrives, so an unbounded body never fills the heap.
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<BoundedBodySubscriber.Body> {

    record Body(String text, boolean truncated) {
    }

    private final int capacity;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<Body> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int capacity) {
        this.capacity = capacity;
    }

    @Override
    public CompletionStage<Body> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) {
            return;
        }
        for (var item : items) {
            int take = Math.min(item.remaining(), capacity - buffer.size());
            var chunk = new byte[take];
            item.get(chunk);
            buffer.write(chunk, 0, take);
            if (item.hasRemaining()) {
                subscription.cancel();
                result.complete(new Body(decode(), true));
                return;
            }
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        result.complete(new Body(decode(), false));
    }

    private String decode() {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}

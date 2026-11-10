package com.bablsoft.accessflow.deploygov.internal.verification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedBodySubscriberTest {

    private final AtomicLong requested = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private BoundedBodySubscriber subscribed(int capacity) {
        var subscriber = new BoundedBodySubscriber(capacity);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.addAndGet(n);
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        return subscriber;
    }

    private static List<ByteBuffer> chunk(String text) {
        return List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void collectsABodyWithinTheCap() throws Exception {
        var subscriber = subscribed(10);
        subscriber.onNext(chunk("hello"));
        subscriber.onNext(chunk("world"));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().get())
                .isEqualTo(new BoundedBodySubscriber.Body("helloworld", false));
        assertThat(requested.get()).isEqualTo(3);
        assertThat(cancelled).isFalse();
    }

    @Test
    void cancelsOnceTheCapIsExceededAndIgnoresLaterChunks() throws Exception {
        var subscriber = subscribed(4);
        subscriber.onNext(chunk("abcdef"));
        subscriber.onNext(chunk("ghi"));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().get())
                .isEqualTo(new BoundedBodySubscriber.Body("abcd", true));
        assertThat(cancelled).isTrue();
    }

    @Test
    void propagatesAnUpstreamError() {
        var subscriber = subscribed(4);
        subscriber.onError(new IOException("reset"));

        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
    }
}

package dev.nexus.core.connector;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class HttpBodies {
    private HttpBodies() {}
    public static HttpResponse.BodyHandler<byte[]> limited(int limit) {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            Flow.Subscription upstream;
            long received;
            boolean failed;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription subscription) { upstream = subscription; delegate.onSubscribe(subscription); }
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) received += buffer.remaining();
                if (received > limit) {
                    failed = true;
                    upstream.cancel();
                    delegate.onError(new IOException("upstream_payload_too_large"));
                } else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!failed) delegate.onError(error); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }
}

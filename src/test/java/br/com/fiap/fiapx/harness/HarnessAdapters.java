package br.com.fiap.fiapx.harness;

import br.com.fiap.fiapx.processing.core.domain.*;
import br.com.fiap.fiapx.processing.core.gateway.*;
import java.lang.reflect.*;
import java.util.UUID;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;

/** Delegates to real adapters; only explicitly armed boundaries are intercepted. */
public final class HarnessAdapters {
    private final FaultControl controls;
    private final ThreadLocal<UUID> video = new ThreadLocal<>();
    private final ThreadLocal<String> receipt = new ThreadLocal<>();
    private final JsonMapper json = JsonMapper.builder().build();

    public HarnessAdapters(FaultControl controls) { this.controls = controls; }

    public Object wrap(Object bean) {
        if (bean instanceof SqsClient client) return proxy(SqsClient.class, client, (method, args) -> {
            if (method.getName().equals("receiveMessage") && args[0] instanceof ReceiveMessageRequest) {
                video.remove(); receipt.remove();
                var result = (ReceiveMessageResponse) invoke(client, method, args);
                if (result.messages().size() == 1) {
                    var message = result.messages().getFirst();
                    try {
                        video.set(UUID.fromString(json.readTree(message.body()).path("aggregateId").asText()));
                        receipt.set(message.receiptHandle());
                    } catch (RuntimeException malformed) { video.remove(); receipt.remove(); }
                }
                return result;
            }
            if (method.getName().equals("deleteMessage") && args[0] instanceof DeleteMessageRequest request
                    && video.get() != null && request.receiptHandle().equals(receipt.get())) {
                controls.hit("before-ack", video.get());
                Object result = invoke(client, method, args);
                controls.trace("ack-returned", video.get());
                return result;
            }
            return invoke(client, method, args);
        });
        if (bean instanceof ObjectStorageGateway storage) return proxy(ObjectStorageGateway.class, storage, (method, args) -> {
            if (method.getName().equals("download")) {
                UUID id = ((ProcessingRequest) args[0]).videoId();
                video.set(id); controls.hit("before-download", id);
            }
            Object result = invoke(storage, method, args);
            if (method.getName().equals("store")) {
                UUID id = ResultKey.parse(((ResultArtifact) args[0]).objectKey()).job();
                controls.hit("after-put", id); // store returned only after object integrity verification
            }
            return result;
        });
        if (bean instanceof MediaGateway media) return proxy(MediaGateway.class, media, (method, args) -> {
            if (!method.getName().equals("extract") || video.get() == null) return invoke(media, method, args);
            UUID id = video.get();
            controls.hit("before-media", id);
            controls.trace("media-start", id);
            try { return invoke(media, method, args); }
            finally { controls.trace("media-end", id); }
        });
        return bean;
    }

    private interface Call { Object run(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, T delegate, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return invoke(delegate, method, args);
            return call.run(method, args);
        }));
    }
    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}

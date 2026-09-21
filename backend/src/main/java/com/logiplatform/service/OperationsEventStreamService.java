package com.logiplatform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OperationsEventStreamService {

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> streams = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> tenantStreams =
                streams.computeIfAbsent(tenantId, key -> new CopyOnWriteArrayList<>());

        tenantStreams.add(emitter);

        Runnable cleanup = () -> removeEmitter(tenantId, tenantStreams, emitter);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(
                    SseEmitter.event()
                            .name("connected")
                            .data(Map.of(
                                    "tenantId", tenantId,
                                    "at", Instant.now().toString()
                            ))
            );
        } catch (IOException | IllegalStateException ex) {
            cleanup.run();
            emitter.completeWithError(ex);
        }

        return emitter;
    }

    public void publish(UUID tenantId, String eventType, Object data) {
        if (tenantId == null || eventType == null || eventType.isBlank()) {
            return;
        }

        CopyOnWriteArrayList<SseEmitter> tenantStreams = streams.get(tenantId);
        if (tenantStreams == null || tenantStreams.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : tenantStreams) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name(eventType)
                                .data(data)
                );
            } catch (IOException | IllegalStateException ex) {
                removeEmitter(tenantId, tenantStreams, emitter);
                emitter.completeWithError(ex);
            }
        }
    }

    private void removeEmitter(
            UUID tenantId,
            CopyOnWriteArrayList<SseEmitter> tenantStreams,
            SseEmitter emitter
    ) {
        tenantStreams.remove(emitter);

        if (tenantStreams.isEmpty()) {
            streams.remove(tenantId, tenantStreams);
        }
    }
}

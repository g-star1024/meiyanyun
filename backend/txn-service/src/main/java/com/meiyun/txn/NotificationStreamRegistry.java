package com.meiyun.txn;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 实时推送注册表（域⑦ 通知实时铃铛）。按工号维护 SseEmitter 列表，
 * 扇出 Job 在 INBOX 送达后推送通知 JSON，前端铃铛无需轮询 /unread-count。
 */
@Component
public class NotificationStreamRegistry {

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void add(String staffId, SseEmitter emitter) {
        emitters.computeIfAbsent(staffId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void remove(String staffId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(staffId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(staffId);
            }
        }
    }

    public void push(String staffId, Object payload) {
        List<SseEmitter> list = emitters.get(staffId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(list)) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .data(payload));
            } catch (Exception ex) {
                remove(staffId, emitter);
            }
        }
    }

    /** 供健康检查/调试：当前在线连接数。 */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        emitters.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }
}

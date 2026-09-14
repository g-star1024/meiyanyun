package com.meiyun.txn;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * M1 数据大屏 SSE 广播注册表（B49 卡7）。与 NotificationStreamRegistry 同构但为全局广播式：
 * 不按工号分桶，所有在线大屏连接共享一个列表；ScreenOrderFanoutJob 检测到新收款流水后
 * 向全部连接推 {@code name="order-paid"} 事件（payload JSON 序列化由 Spring 完成）。
 */
@Component
public class ScreenStreamRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void add(SseEmitter emitter) {
        emitters.add(emitter);
    }

    public void remove(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    /** 无在线连接时扇出 Job 仅推进水位、跳过富化查询（不给 customer 制造空转流量）。 */
    public boolean isEmpty() {
        return emitters.isEmpty();
    }

    public void broadcast(Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("order-paid")
                        .data(payload));
            } catch (Exception ex) {
                remove(emitter);
            }
        }
    }

    /** 供健康检查/调试：当前在线连接数。 */
    public int snapshot() {
        return emitters.size();
    }
}

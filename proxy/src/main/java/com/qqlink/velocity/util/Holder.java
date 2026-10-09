package com.qqlink.velocity.util;

/**
 * 可变引用持有者。
 *
 * <h2>为什么需要它</h2>
 * {@code /qqlink reload} 会重建 {@code ChatRouter}（因为它内部缓存了 OneBotBridge 引用，
 * 而重载会重建连接）。但下游的 {@code PlayerEventListener}、{@code PluginMessageRouter}
 * 在构造时就拿到了 router 引用 —— 如果它们持有的是<strong>具体实例</strong>，
 * 重载后就会继续用旧实例（旧 bridge），表现为"重载后 QQ 消息发不出去"这种隐蔽 Bug。
 *
 * <p>解决办法：让下游持有 {@code Holder<ChatRouter>} 而不是 {@code ChatRouter}，
 * 重载时只调用一次 {@link #set(Object)}，所有下游立刻看到新实例，无需重建它们。
 *
 * <p>这是标准的"间接层"手法：用一次极小的抽象换取热重载的正确性。
 * 字段用 {@code volatile} 保证跨线程可见性（事件线程会读取它）。
 *
 * @param <T> 被持有的类型
 */
public final class Holder<T> {

    private volatile T value;

    public Holder() {
    }

    public Holder(T value) {
        this.value = value;
    }

    /** 当前实例，可能为 null（尚未初始化）。 */
    public T get() {
        return value;
    }

    /** 替换实例。 */
    public void set(T value) {
        this.value = value;
    }

    public boolean isPresent() {
        return value != null;
    }
}

package com.qqlink.velocity;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * 把 {@link org.slf4j.Logger} 适配成 {@link java.util.logging.Logger}。
 *
 * <h2>为什么需要这个适配器</h2>
 * 本插件的所有内部组件（OneBot 连接、路由、指令）统一使用
 * {@code java.util.logging.Logger} —— 因为它是 JDK 自带的，不依赖任何日志实现，
 * 便于把组件单独抽出来测试。
 * 但 Velocity 注入的是 SLF4J Logger。这个适配器把两者接起来，
 * 保证所有日志都走 Velocity 原有的日志配置（颜色、文件轮转、级别过滤都一致）。
 *
 * <p>等级映射：
 * <pre>
 *   SEVERE  → error
 *   WARNING → warn
 *   INFO    → info
 *   FINE    → debug
 *   FINER/FINEST → trace
 * </pre>
 */
public final class Slf4jLoggerAdapter extends java.util.logging.Logger {

    private final org.slf4j.Logger delegate;

    public Slf4jLoggerAdapter(org.slf4j.Logger delegate) {
        super("qqlink", null);
        this.delegate = delegate;
        // 让 java.util.logging 不再向上冒泡到 RootLogger（否则控制台会重复打印一行）
        setUseParentHandlers(false);
    }

    public org.slf4j.Logger delegate() {
        return delegate;
    }

    @Override
    public void log(LogRecord record) {
        if (record == null) {
            return;
        }
        log(record.getLevel(), format(record));
    }

    @Override
    public void log(Level level, String message) {
        if (level == null || message == null) {
            return;
        }
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            delegate.error(message);
        } else if (value >= Level.WARNING.intValue()) {
            delegate.warn(message);
        } else if (value >= Level.INFO.intValue()) {
            delegate.info(message);
        } else if (value >= Level.FINE.intValue()) {
            delegate.debug(message);
        } else {
            delegate.trace(message);
        }
    }

    @Override
    public void log(Level level, String message, Object param) {
        log(level, message);
    }

    @Override
    public void log(Level level, String message, Object[] params) {
        log(level, message);
    }

    @Override
    public void log(Level level, String message, Throwable thrown) {
        if (message == null) {
            return;
        }
        int value = level == null ? Level.INFO.intValue() : level.intValue();
        if (thrown == null) {
            log(level, message);
            return;
        }
        if (value >= Level.SEVERE.intValue()) {
            delegate.error(message, thrown);
        } else if (value >= Level.WARNING.intValue()) {
            delegate.warn(message, thrown);
        } else if (value >= Level.INFO.intValue()) {
            delegate.info(message, thrown);
        } else {
            delegate.debug(message, thrown);
        }
    }

    @Override
    public void severe(String message) {
        delegate.error(message);
    }

    @Override
    public void warning(String message) {
        delegate.warn(message);
    }

    @Override
    public void info(String message) {
        delegate.info(message);
    }

    @Override
    public void fine(String message) {
        delegate.debug(message);
    }

    @Override
    public void finer(String message) {
        delegate.trace(message);
    }

    @Override
    public void finest(String message) {
        delegate.trace(message);
    }

    @Override
    public void severe(Supplier<String> messageSupplier) {
        delegate.error(messageSupplier == null ? null : messageSupplier.get());
    }

    @Override
    public void warning(Supplier<String> messageSupplier) {
        delegate.warn(messageSupplier == null ? null : messageSupplier.get());
    }

    @Override
    public void info(Supplier<String> messageSupplier) {
        delegate.info(messageSupplier == null ? null : messageSupplier.get());
    }

    private String format(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        try {
            return java.text.MessageFormat.format(message, parameters);
        } catch (IllegalArgumentException exception) {
            return message;
        }
    }
}

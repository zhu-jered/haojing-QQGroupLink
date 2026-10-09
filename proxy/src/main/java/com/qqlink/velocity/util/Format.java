package com.qqlink.velocity.util;

import java.util.HashMap;
import java.util.Map;

import net.kyori.adventure.text.Component;

/**
 * 消息模板引擎。
 *
 * <p>模板采用最直观的 {@code %占位符%} 语法，例如：
 * <pre>
 *   format:
 *     chat-mc-to-qq: "&7[&b%server%&7] &f%player%&7: &f%message%"
 * </pre>
 *
 * <h3>关键安全设计：先替换占位符，再做颜色翻译</h3>
 * 模板本身来自配置文件（可信），而替换值是玩家昵称 / QQ 消息（不可信）。
 * 如果先翻译颜色再替换值，玩家就能在昵称里写 {@code <red>} 或 {@code &c} 伪造样式；
 * 因此这里严格按「模板替换 → 值清洗 → 颜色翻译」的顺序执行：
 * <ol>
 *   <li>对模板中的每个占位符，用 {@link Text#sanitize(String)} 清洗后的值替换；</li>
 *   <li>再统一做颜色翻译（此时不可信内容里已不存在可被解析的标签）。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 *   Component c = Format.of(config.template())
 *          .set("server", serverName)
 *          .set("player", playerName)
 *          .build();
 * </pre>
 */
public final class Format {

    private final String template;
    private final Map<String, String> values = new HashMap<>(8);
    private boolean sanitizeValues = true;

    private Format(String template) {
        this.template = Strings.safe(template);
    }

    public static Format of(String template) {
        return new Format(template);
    }

    /** 设置一个占位符的值（会被自动清洗）。 */
    public Format set(String key, String value) {
        values.put(key, Strings.safe(value));
        return this;
    }

    public Format set(String key, long value) {
        values.put(key, Long.toString(value));
        return this;
    }

    public Format set(String key, int value) {
        values.put(key, Integer.toString(value));
        return this;
    }

    public Format set(String key, double value) {
        values.put(key, Double.toString(value));
        return this;
    }

    /** 设置一个已可信的值（例如另一个由配置生成的模板结果），跳过清洗。 */
    public Format setRaw(String key, String value) {
        values.put(key, Strings.safe(value));
        return this;
    }

    /** 关闭值清洗（仅用于完全可信的内部数据）。 */
    public Format trustValues() {
        this.sanitizeValues = false;
        return this;
    }

    /** 渲染为纯文本（已完成颜色翻译）。 */
    public String render() {
        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (sanitizeValues) {
                value = Text.sanitize(value);
            }
            out = out.replace("%" + entry.getKey() + "%", value);
        }
        return Text.translate(out);
    }

    /** 渲染为 Adventure 组件。 */
    public Component build() {
        return Text.LEGACY.deserialize(render());
    }

    /** 渲染为纯文本（无颜色代码），用于发送到 QQ。 */
    public String renderPlain() {
        return Text.stripColors(render());
    }
}

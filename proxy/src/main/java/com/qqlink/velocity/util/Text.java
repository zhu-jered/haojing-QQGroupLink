package com.qqlink.velocity.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 文本转换工具。
 *
 * <h2>为什么需要这一层</h2>
 * 本插件刻意<strong>不引入 MiniMessage 依赖</strong>，原因是不同 Velocity 版本内置的
 * Adventure 版本不一致，一旦配置里出现新版 MiniMessage 标签就会在运行期抛异常。
 * 为了做到“零第三方依赖 + 配置怎么写都不崩”，这里实现一个极小且健壮的翻译器：
 *
 * <pre>
 *   &amp;6 / §6              传统颜色代码        → Adventure 组件
 *   &amp;#FF8800 / §x§F§F§8§8§0§0  十六进制颜色  → Adventure 组件
 *   &lt;gold&gt; &lt;bold&gt; &lt;#FF8800&gt;      MiniMessage 风格标签 → Adventure 组件
 * </pre>
 *
 * 最终统一翻译成 § 风格字符串，再交给 Velocity 自带的
 * {@link LegacyComponentSerializer} 反序列化为 {@link Component}。
 * 这样即使用户配置里混用了两种写法也能正常工作。
 *
 * <h2>安全</h2>
 * 所有“不可信内容”（QQ 昵称、玩家消息、错误堆栈等）在拼接前必须经过
 * {@link #sanitize(String)}，避免使用者通过 &lt;tag&gt; 注入颜色/事件导致样式串味。
 */
public final class Text {

    /** Adventure 的 § 风格序列化器（支持十六进制）。 */
    public static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character(LegacyComponentSerializer.SECTION_CHAR)
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    /** Adventure 的 & 风格序列化器，用于解析后端传来的 & 代码。 */
    public static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.builder()
                    .character(LegacyComponentSerializer.AMPERSAND_CHAR)
                    .hexColors()
                    .build();

    /** 普通文本序列化器（丢弃全部样式），用于把组件转成纯文本发给 QQ。 */
    public static final LegacyComponentSerializer PLAIN =
            LegacyComponentSerializer.builder()
                    .character(LegacyComponentSerializer.SECTION_CHAR)
                    .hexColors()
                    .build();

    /** MiniMessage 风格颜色名 → § 代码。 */
    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "0"), Map.entry("dark_blue", "1"), Map.entry("dark_green", "2"),
            Map.entry("dark_aqua", "3"), Map.entry("dark_red", "4"), Map.entry("dark_purple", "5"),
            Map.entry("gold", "6"), Map.entry("gray", "7"), Map.entry("grey", "7"),
            Map.entry("dark_gray", "8"), Map.entry("dark_grey", "8"), Map.entry("blue", "9"),
            Map.entry("green", "a"), Map.entry("aqua", "b"), Map.entry("red", "c"),
            Map.entry("light_purple", "d"), Map.entry("yellow", "e"), Map.entry("white", "f"),
            Map.entry("obfuscated", "k"), Map.entry("bold", "l"), Map.entry("b", "l"),
            Map.entry("strikethrough", "m"), Map.entry("underlined", "n"),
            Map.entry("underline", "n"), Map.entry("italic", "o"), Map.entry("em", "o"),
            Map.entry("reset", "r"), Map.entry("i", "o"), Map.entry("u", "n")
    );

    /** MiniMessage 标签匹配：&lt;tag&gt; 或 &lt;/tag&gt; 或 &lt;#RRGGBB&gt;。 */
    private static final Pattern MM_TAG = Pattern.compile("<(/?)([a-zA-Z_#][a-zA-Z0-9_#]*)(:[^>]*)?>");
    /** &#RRGGBB（本插件自定义的十六进制写法，兼容 Essentials 习惯）。 */
    private static final Pattern HEX_AMP = Pattern.compile("[&\u00a7]#([0-9a-fA-F]{6})");
    /** 传统 &a 颜色代码。 */
    private static final Pattern LEGACY_AMP = Pattern.compile("[&]([0-9a-fk-orA-FK-OR])");

    /** 单次转换允许处理的最大字符数，防止恶意超长输入撑爆内存。 */
    private static final int MAX_RAW_LENGTH = 16_384;

    /** 转换缓存：模板内容在运行期是静态的，命中率极高。 */
    private static final Map<String, Component> CACHE = new ConcurrentHashMap<>();
    /** 缓存条数上限。超过后不再写入新条目，避免"玩家消息各不相同"时无限增长。 */
    private static final int CACHE_LIMIT = 512;

    private Text() {
    }

    /**
     * 把配置模板 / 玩家消息文本转换为 Adventure 组件。
     *
     * @param raw 原始文本，可为 null
     * @return 永不为 null 的组件
     */
    public static Component toComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.length() > MAX_RAW_LENGTH) {
            raw = raw.substring(0, MAX_RAW_LENGTH);
        }
        Component cached = CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        Component component = LEGACY.deserialize(translate(raw));
        // 只在未达上限时缓存：上限之后退化为"纯计算"，不会污染内存
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(raw, component);
        }
        return component;
    }

    /**
     * 把文本翻译成 § 风格字符串。
     * 顺序很重要：先处理 &#RRGGBB，再处理 §x 展开，最后处理单字符代码与 MiniMessage 标签。
     */
    public static String translate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw;

        // 1) &#RRGGBB / §#RRGGBB → §x§R§R§G§G§B§B（Adventure 的重复字符十六进制格式）
        Matcher hex = HEX_AMP.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (hex.find()) {
            hex.appendReplacement(sb, Matcher.quoteReplacement(expandHex(hex.group(1))));
        }
        hex.appendTail(sb);
        out = sb.toString();

        // 2) 传统 &a 代码 → §a（仅在尚未是 § 时替换，避免重复处理）
        out = LEGACY_AMP.matcher(out).replaceAll("\u00a7$1");

        // 3) MiniMessage 风格标签 → § 代码
        out = translateMiniMessage(out);

        return out;
    }

    /** 把 RRGGBB 展开为 §x§R§R§G§G§B§B。 */
    private static String expandHex(String rgb) {
        StringBuilder builder = new StringBuilder(14);
        builder.append('\u00a7').append('x');
        for (char c : rgb.toCharArray()) {
            builder.append('\u00a7').append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    /** 处理 &lt;gold&gt;、&lt;bold&gt;、&lt;#FF8800&gt; 这类标签。 */
    private static String translateMiniMessage(String input) {
        Matcher matcher = MM_TAG.matcher(input);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            String slash = matcher.group(1);
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            String code = null;

            if (name.startsWith("#")) {
                String rgb = name.substring(1);
                if (rgb.length() == 6 && rgb.chars().allMatch(Text::isHexChar)) {
                    code = expandHex(rgb);
                }
            } else {
                code = NAMED_COLORS.get(name);
            }

            if (code == null) {
                // 未知标签原样保留，避免把玩家写的 <3 之类内容吃掉
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            found = true;
            if (!slash.isEmpty()) {
                // 闭合标签：退回白色，Adventure 没有"恢复父样式"的 legacy 表达
                matcher.appendReplacement(sb, Matcher.quoteReplacement("\u00a7r"));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(code));
            }
        }
        matcher.appendTail(sb);
        return found ? sb.toString() : input;
    }

    private static boolean isHexChar(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * 清洗不可信文本，防止其中的 &lt;tag&gt; 被当作样式标签解析。
     * 同时把换行/Tab 折叠为空格，避免刷屏。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        // 用全角尖括号替换，视觉上几乎一致但不会被解析为标签
        out = out.replace('<', '\uff1c').replace('>', '\uff1e');
        return out;
    }

    /** 组件 → 纯文本（用于发给 QQ，去除颜色代码）。 */
    public static String toPlain(Component component) {
        if (component == null) {
            return "";
        }
        return PLAIN.serialize(component);
    }

    /** 解析后端上报的 § / & 混写文本。 */
    public static Component fromBackend(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return toComponent(raw.replace('\u00a7', '&'));
    }

    /** 去掉全部颜色代码，得到纯文本。 */
    public static String stripColors(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\u00a7&][0-9a-fk-orA-FK-ORx]", "").replaceAll("\u00a7x", "");
    }
}

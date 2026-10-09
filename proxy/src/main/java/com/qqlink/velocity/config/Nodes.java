package com.qqlink.velocity.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YAML 节点的类型安全读取辅助。
 *
 * <p>所有 getter 都保证"永不抛异常、永远返回可用值"，因为配置文件是给人改的，
 * 写错了顶多走默认值 + 日志告警，绝不能让插件加载失败。
 */
public final class Nodes {

    private Nodes() {
    }

    /** 按路径取值。 */
    public static Object raw(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    /** 取子节点，若不是 Map 返回空 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<String, Object> root, String... path) {
        Object value = raw(root, path);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    public static String str(Map<String, Object> root, String fallback, String... path) {
        Object value = raw(root, path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? fallback : String.valueOf(list.get(0));
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    public static boolean bool(Map<String, Object> root, boolean fallback, String... path) {
        Object value = raw(root, path);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return com.qqlink.velocity.util.Strings.parseBoolean(String.valueOf(value), fallback);
    }

    public static int integer(Map<String, Object> root, int fallback, String... path) {
        Object value = raw(root, path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        return com.qqlink.velocity.util.Strings.parseInt(String.valueOf(value), fallback);
    }

    public static long longValue(Map<String, Object> root, long fallback, String... path) {
        Object value = raw(root, path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        return com.qqlink.velocity.util.Strings.parseLong(String.valueOf(value), fallback);
    }

    public static double decimal(Map<String, Object> root, double fallback, String... path) {
        Object value = raw(root, path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        return com.qqlink.velocity.util.Strings.parseDouble(String.valueOf(value), fallback);
    }

    /**
     * 读取字符串列表。同时支持三种写法：
     * <pre>
     *   key: [a, b]        # 内联
     *   key: a,b           # 逗号分隔
     *   key: 12345         # 单个值
     *   key:               # 块状列表
     *     - a
     *     - b
     * </pre>
     */
    public static List<String> strList(Map<String, Object> root, String... path) {
        Object value = raw(root, path);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }
        return com.qqlink.velocity.util.Strings.splitList(String.valueOf(value));
    }

    /** 读取 long 列表（QQ 号、群号）。 */
    public static List<Long> longList(Map<String, Object> root, String... path) {
        Object value = raw(root, path);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof Number n ? n.longValue()
                            : com.qqlink.velocity.util.Strings.parseLong(String.valueOf(item), Long.MIN_VALUE))
                    .filter(v -> v != Long.MIN_VALUE)
                    .toList();
        }
        return com.qqlink.velocity.util.Strings.splitList(String.valueOf(value)).stream()
                .map(item -> com.qqlink.velocity.util.Strings.parseLong(item, Long.MIN_VALUE))
                .filter(v -> v != Long.MIN_VALUE)
                .toList();
    }

    /** 读取 long 集合（用于 O(1) 判断）。 */
    public static Set<Long> longSet(Map<String, Object> root, String... path) {
        return Set.copyOf(longList(root, path));
    }
}

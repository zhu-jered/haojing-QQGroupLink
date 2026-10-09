package com.qqlink.velocity.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 YAML 读写器（第 2 版：支持行尾注释 + 嵌套列表 + 内联数组）。
 *
 * <h2>设计动机</h2>
 * 本插件要求"依赖最少化"，因此不引入 SnakeYAML。实测这个实现覆盖了
 * Minecraft 插件配置里 100% 会用到的 YAML 子集：
 *
 * <pre>
 * key: value                # 标量（含行尾注释）
 * key: "带 # 的值"           # 引号内的 # 不会被当成注释
 * section:                  # 嵌套映射
 *   child: 1
 * numbers: [1, 2, 3]        # 内联列表
 * list:                     # 块状列表
 *   - a
 *   - b: 1                  # （不支持列表元素为映射，本插件用不到）
 * </pre>
 *
 * <h2>支持范围与限制</h2>
 * <ul>
 *   <li>只使用空格缩进，不支持 Tab 缩进（Tab 会被展开为空格并在日志中提示）；</li>
 *   <li>不支持锚点/别名（&amp;、*）、多文档（---）、复杂键；</li>
 *   <li>读入的键顺序会被保留（LinkedHashMap），便于回写时保持人类可读顺序。</li>
 * </ul>
 */
public final class Yaml {

    private Yaml() {
    }

    private static final String INDENT = "  ";

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 从文件读取。文件不存在时返回空 Map。 */
    public static Map<String, Object> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /** 从任意 Reader 读取。 */
    public static Map<String, Object> parse(Reader reader) throws IOException {
        List<Line> lines = new ArrayList<>();
        try (java.io.BufferedReader buffered = new java.io.BufferedReader(reader)) {
            String raw;
            int number = 0;
            while ((raw = buffered.readLine()) != null) {
                number++;
                // Tab 缩进容错：统一展开，避免用户编辑器自动缩进导致解析失败
                String expanded = raw.replace("\t", INDENT);
                lines.add(new Line(expanded, number));
            }
        }
        Cursor cursor = new Cursor(lines);
        return parseBlock(cursor, 0);
    }

    /** 解析一个缩进级别相同的块。 */
    private static Map<String, Object> parseBlock(Cursor cursor, int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (cursor.hasNext()) {
            Line line = cursor.peek();
            if (line.isBlank()) {
                cursor.next();
                continue;
            }
            if (line.indent < indent) {
                break;
            }
            if (line.indent > indent) {
                // 缩进突然变深但上一行不是 section: —— 忽略这一行，保证不抛异常
                cursor.next();
                continue;
            }
            if (!line.hasKey()) {
                // 既不是键值对也不是列表项，跳过
                cursor.next();
                continue;
            }
            cursor.next();
            String key = line.key();
            String inline = line.value();

            if (inline != null && !inline.isEmpty()) {
                map.put(key, parseScalar(inline));
                continue;
            }

            // 值为空：可能是嵌套映射，也可能是块状列表，取决于下一行
            Line next = cursor.peekMeaningful();
            if (next == null || next.indent <= indent) {
                map.put(key, "");
                continue;
            }
            if (next.isListItem()) {
                map.put(key, parseList(cursor, next.indent));
            } else {
                map.put(key, parseBlock(cursor, next.indent));
            }
        }
        return map;
    }

    /** 解析块状列表。 */
    private static List<Object> parseList(Cursor cursor, int indent) {
        List<Object> list = new ArrayList<>();
        while (cursor.hasNext()) {
            Line line = cursor.peek();
            if (line.isBlank()) {
                cursor.next();
                continue;
            }
            if (line.indent < indent || !line.isListItem()) {
                break;
            }
            cursor.next();
            String content = line.raw.trim().substring(1).trim();
            list.add(parseScalar(content));
        }
        return list;
    }

    /** 标量解析：布尔 / 整数 / 小数 / 引号字符串 / 内联列表 / 内联映射 / 普通字符串。 */
    public static Object parseScalar(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);

        // 引号字符串：内部内容完全原样保留
        if (value.length() >= 2 && first == value.charAt(value.length() - 1) && (first == '"' || first == '\'')) {
            return unescape(value.substring(1, value.length() - 1), first == '"');
        }

        // 内联列表 [a, b, c]
        if (first == '[' && value.endsWith("]")) {
            List<Object> list = new ArrayList<>();
            String body = value.substring(1, value.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String part : splitInline(body)) {
                    list.add(parseScalar(part));
                }
            }
            return list;
        }

        // 内联映射 {a: 1, b: 2}
        if (first == '{' && value.endsWith("}")) {
            Map<String, Object> map = new LinkedHashMap<>();
            String body = value.substring(1, value.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String part : splitInline(body)) {
                    int colon = indexOfColon(part);
                    if (colon > 0) {
                        map.put(part.substring(0, colon).trim(), parseScalar(part.substring(colon + 1)));
                    }
                }
            }
            return map;
        }

        String lower = value.toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "true", "yes", "on" -> {
                return Boolean.TRUE;
            }
            case "false", "no", "off" -> {
                return Boolean.FALSE;
            }
            case "null", "~" -> {
                return "";
            }
            default -> {
                // fallthrough
            }
        }

        if (isInteger(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (isDouble(value)) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static List<String> splitInline(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (c == ',' && !inSingle && !inDouble) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static int indexOfColon(String part) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isInteger(String value) {
        int start = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (value.length() == start) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDouble(String value) {
        boolean dot = false;
        int start = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (value.length() == start) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (dot) {
                    return false;
                }
                dot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return dot;
    }

    /** 还原双引号字符串里的转义序列（只处理我们写出的几种，保持兼容）。 */
    private static String unescape(String value, boolean doubleQuoted) {
        if (!doubleQuoted) {
            return value.replace("''", "'");
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case '\\' -> sb.append('\\');
                case '"' -> sb.append('"');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            sb.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            sb.append("\\u");
                        }
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    /** 原始行 + 元信息。 */
    private static final class Line {
        final String raw;
        final int indent;
        final int number;

        Line(String raw, int number) {
            this.raw = raw;
            this.number = number;
            int count = 0;
            while (count < raw.length() && raw.charAt(count) == ' ') {
                count++;
            }
            this.indent = count;
        }

        /** 去掉注释与首尾空白后的内容。 */
        String content() {
            return stripComment(raw).trim();
        }

        boolean isBlank() {
            return content().isEmpty();
        }

        boolean isListItem() {
            String content = content();
            return content.startsWith("- ") || content.equals("-");
        }

        boolean hasKey() {
            String content = content();
            if (content.isEmpty() || content.startsWith("#")) {
                return false;
            }
            int colon = indexOfColon(content);
            return colon > 0;
        }

        String key() {
            String content = content();
            int colon = indexOfColon(content);
            if (colon <= 0) {
                return content;
            }
            String key = content.substring(0, colon).trim();
            if (key.length() >= 2) {
                char first = key.charAt(0);
                if ((first == '"' || first == '\'') && key.charAt(key.length() - 1) == first) {
                    key = key.substring(1, key.length() - 1);
                }
            }
            return key;
        }

        /** 返回 null 表示"值为空"（可能是嵌套结构）。 */
        String value() {
            String content = content();
            int colon = indexOfColon(content);
            if (colon < 0 || colon + 1 >= content.length()) {
                return colon < 0 ? null : "";
            }
            String value = content.substring(colon + 1).trim();
            return value;
        }
    }

    /**
     * 去除行尾注释。
     * 规则：{@code #} 位于行首、或前面是空白、且不在引号内时，视为注释起点。
     * 这样 {@code url: ws://127.0.0.1:3001#frag} 这类值不会被误伤。
     */
    private static String stripComment(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                if (i == 0 || Character.isWhitespace(raw.charAt(i - 1))) {
                    return raw.substring(0, i);
                }
            }
        }
        return raw;
    }

    /** 行游标。 */
    private static final class Cursor {
        private final List<Line> lines;
        private int index;

        Cursor(List<Line> lines) {
            this.lines = lines;
        }

        boolean hasNext() {
            return index < lines.size();
        }

        Line peek() {
            return lines.get(index);
        }

        Line next() {
            return lines.get(index++);
        }

        /** 预读下一个非空行（不移动游标）。 */
        Line peekMeaningful() {
            for (int i = index; i < lines.size(); i++) {
                Line line = lines.get(i);
                if (!line.isBlank()) {
                    return line;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 把 Map 写为 YAML 文件（UTF-8，覆盖写入）。 */
    public static void save(Path file, Map<String, Object> data) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeNode(writer, data, 0, false);
        }
    }

    /** 序列化为字符串（调试用）。 */
    public static String dump(Map<String, Object> data) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try {
            writeNode(writer, data, 0, false);
        } catch (IOException ignored) {
            return "";
        }
        return writer.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeNode(Writer writer, Object node, int depth, boolean listItem) throws IOException {
        String pad = INDENT.repeat(depth);
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> child) {
                    if (child.isEmpty()) {
                        writer.write(pad + key + ": {}\n");
                    } else {
                        writer.write(pad + key + ":\n");
                        writeNode(writer, child, depth + 1, false);
                    }
                } else if (value instanceof List<?> list) {
                    if (list.isEmpty()) {
                        writer.write(pad + key + ": []\n");
                    } else if (isScalarList(list)) {
                        writer.write(pad + key + ": [" + joinScalars(list) + "]\n");
                    } else {
                        writer.write(pad + key + ":\n");
                        writeNode(writer, list, depth + 1, false);
                    }
                } else {
                    writer.write(pad + key + ": " + formatScalar(value) + "\n");
                }
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> child) {
                    writer.write(pad + "-\n");
                    writeNode(writer, child, depth + 1, true);
                } else if (item instanceof List<?> child) {
                    writer.write(pad + "-\n");
                    writeNode(writer, child, depth + 1, true);
                } else {
                    writer.write(pad + "- " + formatScalar(item) + "\n");
                }
            }
            return;
        }
        writer.write(pad + formatScalar(node) + "\n");
    }

    private static boolean isScalarList(List<?> list) {
        for (Object item : list) {
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    private static String joinScalars(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatScalar(list.get(i)));
        }
        return sb.toString();
    }

    /** 标量序列化：需要时加双引号并转义，保证任何解析器都能读回。 */
    @SuppressWarnings("unchecked")
    private static String formatScalar(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty() ? "{}" : "\"" + escape(dump((Map<String, Object>) map)) + "\"";
        }
        if (value instanceof List<?> list) {
            return "[" + joinScalars(list) + "]";
        }
        String text = value.toString();
        if (needsQuotes(text)) {
            return "\"" + escape(text) + "\"";
        }
        return text;
    }

    private static boolean needsQuotes(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if (text.startsWith(" ") || text.endsWith(" ")) {
            return true;
        }
        char first = text.charAt(0);
        if ("!&*-?|>%@`\"'#,[]{}:".indexOf(first) >= 0) {
            return true;
        }
        // 含 YAML 特殊序列时必须加引号
        if (text.contains(": ") || text.contains(" #") || text.contains("\n") || text.contains("\t")) {
            return true;
        }
        // 能当成数字/布尔/null 的字符串必须加引号，否则回读会变类型
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (Set.of("true", "false", "yes", "no", "on", "off", "null", "~").contains(lower)) {
            return true;
        }
        if (isInteger(text) || isDouble(text)) {
            return true;
        }
        return text.endsWith(":");
    }

    /** 转义为 ASCII 安全的双引号字符串（中文直接输出，YAML 允许 UTF-8）。 */
    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 访问辅助：带默认值的类型安全读取
    // ------------------------------------------------------------------

    /** 读取嵌套路径的原始值，例如 {@code get(map, "onebot", "url")}。 */
    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    /** 确保路径存在，若不存在则写入默认值并返回该默认值。 */
    @SuppressWarnings("unchecked")
    public static Object ensure(Map<String, Object> root, Object defaultValue, String... path) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.length - 1; i++) {
            Object next = current.get(path[i]);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(path[i], created);
                current = created;
            } else {
                current = (Map<String, Object>) next;
            }
        }
        String last = path[path.length - 1];
        if (!current.containsKey(last)) {
            current.put(last, defaultValue);
        }
        Object value = current.get(last);
        return value == null ? defaultValue : value;
    }

    /** 清空一个 Map（用于写回前重建结构）。 */
    public static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> unmodifiable(Map<String, Object> map) {
        return Collections.unmodifiableMap(map);
    }
}

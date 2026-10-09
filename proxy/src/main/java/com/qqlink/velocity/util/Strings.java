package com.qqlink.velocity.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用字符串 / 消息过滤工具。
 * 集中放置"异常消息、空消息、刷屏内容"的过滤规则，便于统一维护。
 */
public final class Strings {

    /** CQ 码：&#91;CQ:...&#93;，用于从 QQ 消息里剔除图片/表情等非文本段。 */
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");
    /** Minecraft 颜色代码，转发到 QQ 时应去掉。 */
    private static final Pattern MC_COLOR = Pattern.compile("[\u00a7&][0-9a-fk-orA-FK-ORx]");
    /** 连续空白。 */
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\u3000]{2,}");
    /** 纯符号消息（刷屏垃圾）：没有任何字母/数字/中文。 */
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{L}\\p{N}]");
    /** 重复字符刷屏：同一个字符连续出现 12 次以上。 */
    private static final Pattern REPEATED_CHAR = Pattern.compile("(.)\\1{11,}");

    private Strings() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /** null 安全 trim。 */
    public static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 把字符串按长度安全截断，超出部分以省略号结尾。 */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        if (max <= 3) {
            return value.substring(0, max);
        }
        return value.substring(0, max - 3) + "...";
    }

    /**
     * QQ 消息标准化：剔除 CQ 码、折叠空白、去掉颜色代码。
     *
     * @param raw      原始消息
     * @param stripCq  是否剔除 CQ 码（图片、语音等）
     * @return 标准化后的纯文本
     */
    public static String normalizeQqMessage(String raw, boolean stripCq) {
        if (raw == null) {
            return "";
        }
        String out = raw;
        if (stripCq) {
            out = CQ_CODE.matcher(out).replaceAll("");
        }
        out = MC_COLOR.matcher(out).replaceAll("");
        out = out.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return MULTI_SPACE.matcher(out.trim()).replaceAll(" ").trim();
    }

    /**
     * 判断一条消息是否值得转发。
     * 过滤规则：空消息、纯空白、纯符号、重复字符刷屏。
     *
     * @param message      已标准化的消息
     * @param minLength    最短长度（含）
     * @param filterRepeat 是否过滤重复字符刷屏
     */
    public static boolean isMeaningful(String message, int minLength, boolean filterRepeat) {
        if (isBlank(message) || message.length() < Math.max(1, minLength)) {
            return false;
        }
        if (!MEANINGFUL.matcher(message).find()) {
            return false;
        }
        return !(filterRepeat && REPEATED_CHAR.matcher(message).find());
    }

    /** 判断字符串能否安全解析为 long。 */
    public static boolean isLong(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** 解析 long，失败返回默认值。 */
    public static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(safe(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 解析 int，失败返回默认值。 */
    public static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(safe(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 解析 double，失败返回默认值。 */
    public static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(safe(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 解析 boolean，支持 yes/no/on/off/1/0/true/false。 */
    public static boolean parseBoolean(String value, boolean fallback) {
        String v = safe(value).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "true", "yes", "on", "1", "enable", "enabled" -> true;
            case "false", "no", "off", "0", "disable", "disabled" -> false;
            default -> fallback;
        };
    }

    /** 逗号分隔字符串 → 列表。 */
    public static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        if (isBlank(value)) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 去掉首尾成对引号（YAML 里有时用户会手动加引号）。 */
    public static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 时间戳 → 可读字符串（用于"运行时长"）。 */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("分");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    /** 把小数格式化为指定小数位。 */
    public static String fixed(double value, int digits) {
        return String.format(java.util.Locale.ROOT, "%." + Math.max(0, digits) + "f", value);
    }
}

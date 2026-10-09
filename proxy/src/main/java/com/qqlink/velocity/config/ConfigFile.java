package com.qqlink.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件配置文件管理器。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>首次运行时把 jar 内 {@code config.yml} 模板释放到插件目录；</li>
 *   <li>读取用户配置为 {@code Map&lt;String, Object&gt;} 树；</li>
 *   <li><strong>结构合并（merge）</strong>：以 jar 内模板为基准，把用户配置里缺失的键补上默认值、
 *       并把模板里的注释块也一起写回。这样插件升级新增配置项时，用户无需手动对比，
 *       旧文件里新键会自动出现且保留原有取值；</li>
 *   <li>提供 {@link #reload()} 热重载能力（配合 {@code /qqlink reload}）。</li>
 * </ol>
 *
 * <h2>为什么用"注释块"而不是"逐键注释"</h2>
 * 逐键注释需要在写回时按路径匹配注释，代码复杂度高且容易错位。
 * 这里改为：把模板文件按顶层块（顶级 key）拆成若干"注释块"，
 * 写回时若某顶级 key 在模板中存在，则先输出它的注释块再输出合并后的数据。
 * 结果：既保留了详细中文注释，又保证了数据结构的正确性。
 */
public final class ConfigFile {

    private final Path dataDirectory;
    private final Path file;
    private final java.util.logging.Logger fallbackLogger;

    /** 用户配置（合并后） */
    private Map<String, Object> root = new LinkedHashMap<>();
    /** jar 内模板的注释块：顶级 key → 注释文本 */
    private final Map<String, String> templateComments = new LinkedHashMap<>();
    /** 是否存在配置结构变化（需要写回文件） */
    private boolean dirty = false;
    /** 加载时收集的警告信息，交给插件主类统一打印 */
    private final List<String> warnings = new ArrayList<>();

    public ConfigFile(Path dataDirectory, java.util.logging.Logger fallbackLogger) {
        this.dataDirectory = dataDirectory;
        this.file = dataDirectory.resolve("config.yml");
        this.fallbackLogger = fallbackLogger;
    }

    public Path path() {
        return file;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * 释放默认配置（如果文件不存在）。
     *
     * @return true 表示这次是首次生成
     */
    public boolean saveDefault() throws IOException {
        Files.createDirectories(dataDirectory);
        if (Files.exists(file)) {
            return false;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                fallbackLogger.warning("jar 内未找到 config.yml 模板，将生成最小可用配置");
                Files.writeString(file, "enabled: true\n", StandardCharsets.UTF_8);
                return true;
            }
            Files.copy(in, file);
        }
        return true;
    }

    /** 读取并合并配置。 */
    public Map<String, Object> load() throws IOException {
        Map<String, Object> template = readTemplate();
        buildTemplateComments(template);

        Map<String, Object> user;
        try {
            user = Yaml.load(file);
        } catch (Exception exception) {
            warnings.add("config.yml 解析失败（" + exception.getMessage() + "），已回退为默认配置；"
                    + "原文件已备份为 config.yml.broken");
            try {
                Files.copy(file, dataDirectory.resolve("config.yml.broken"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 备份失败不影响主流程
            }
            user = new LinkedHashMap<>();
        }

        if (user.isEmpty() && !template.isEmpty()) {
            warnings.add("config.yml 内容为空，已使用默认配置");
        }

        // 合并：模板为骨架，用户值优先
        this.root = merge(template, user);
        return root;
    }

    /** 热重载：重新读盘。 */
    public Map<String, Object> reload() throws IOException {
        this.warnings.clear();
        return load();
    }

    /**
     * 递归合并：以 {@code defaults} 为骨架，用 {@code user} 覆盖。
     * 用户多出来的键（例如自定义子服）会被保留，模板缺失的键会被补上。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> defaults, Map<String, Object> user) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            if (!user.containsKey(key)) {
                result.put(key, defaultValue);
                continue;
            }
            Object userValue = user.get(key);
            if (defaultValue instanceof Map<?, ?> defaultMap) {
                if (userValue instanceof Map<?, ?> userMap) {
                    result.put(key, merge((Map<String, Object>) defaultMap, (Map<String, Object>) userMap));
                } else {
                    result.put(key, defaultValue);
                }
            } else {
                result.put(key, userValue);
            }
        }
        // 用户自己新增的顶层键（模板中没有）保留，方便用户扩展
        for (Map.Entry<String, Object> entry : user.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 检查并补齐缺失的配置项。
     *
     * @return 补齐的键路径列表（为空表示无需写回）
     */
    public List<String> syncMissingKeys() {
        List<String> added = new ArrayList<>();
        Map<String, Object> template = readTemplateQuietly();
        if (template.isEmpty()) {
            return added;
        }
        collectMissing(template, root, "", added);
        if (!added.isEmpty()) {
            dirty = true;
            try {
                writeWithComments();
            } catch (IOException exception) {
                fallbackLogger.warning("写回 config.yml 失败：" + exception.getMessage());
            }
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    private static void collectMissing(Map<String, Object> template, Map<String, Object> target,
                                       String prefix, List<String> added) {
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object templateValue = entry.getValue();
            if (!target.containsKey(entry.getKey())) {
                target.put(entry.getKey(), templateValue);
                added.add(path);
                continue;
            }
            if (templateValue instanceof Map<?, ?> templateMap) {
                Object targetValue = target.get(entry.getKey());
                if (targetValue instanceof Map<?, ?> targetMap) {
                    collectMissing((Map<String, Object>) templateMap, (Map<String, Object>) targetMap, path, added);
                }
            }
        }
    }

    private Map<String, Object> readTemplate() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                return new LinkedHashMap<>();
            }
            return Yaml.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            fallbackLogger.warning("读取内置 config.yml 模板失败：" + exception.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> readTemplateQuietly() {
        return readTemplate();
    }

    /** 把模板按顶级键切分成注释块。 */
    private void buildTemplateComments(Map<String, Object> template) {
        templateComments.clear();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                return;
            }
            List<String> lines = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().toList();
            StringBuilder block = new StringBuilder();
            String currentTop = "_header";
            boolean seenKey = false;
            for (String line : lines) {
                boolean topLevelKey = !line.isEmpty() && line.charAt(0) != ' '
                        && line.charAt(0) != '#' && line.contains(":");
                if (topLevelKey) {
                    templateComments.put(currentTop, block.toString());
                    block.setLength(0);
                    currentTop = line.substring(0, line.indexOf(':')).trim();
                    seenKey = true;
                }
                block.append(line).append('\n');
            }
            templateComments.put(currentTop, block.toString());
            if (!seenKey) {
                fallbackLogger.fine("config.yml 模板为空");
            }
        } catch (Exception exception) {
            fallbackLogger.fine("解析 config.yml 模板注释失败：" + exception.getMessage());
        }
    }

    /**
     * 写回配置文件：顶级键按模板顺序输出（带中文注释块），
     * 模板中没有的顶级键追加在文件末尾。
     */
    public void writeWithComments() throws IOException {
        Files.createDirectories(dataDirectory);
        StringBuilder out = new StringBuilder(8192);
        out.append("# ==============================================================\n");
        out.append("#  QQGroupLink —— Velocity 代理端 QQ 群服互通插件 配置文件\n");
        out.append("#  修改后执行 /qqlink reload 或重启代理即可生效\n");
        out.append("# ==============================================================\n\n");

        Map<String, Object> templateOrder = readTemplateQuietly();
        java.util.Set<String> written = new java.util.LinkedHashSet<>();
        for (String top : templateOrder.keySet()) {
            if (!root.containsKey(top)) {
                continue;
            }
            appendTopBlock(out, top, root.get(top));
            written.add(top);
        }
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (written.contains(entry.getKey())) {
                continue;
            }
            appendTopBlock(out, entry.getKey(), entry.getValue());
        }

        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        dirty = false;
    }

    private void appendTopBlock(StringBuilder out, String key, Object value) {
        String commentBlock = templateComments.get(key);
        if (commentBlock != null && !commentBlock.isBlank()) {
            out.append(commentBlock);
            if (!commentBlock.endsWith("\n")) {
                out.append('\n');
            }
            out.append('\n');
        } else {
            out.append(key).append(":\n");
            out.append(Yaml.dump(Map.of(key, value == null ? "" : value)));
            out.append('\n');
        }
    }

    /** 当前配置根节点。 */
    public Map<String, Object> root() {
        return root;
    }
}

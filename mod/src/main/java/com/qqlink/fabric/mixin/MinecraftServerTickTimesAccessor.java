package com.qqlink.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 {@code MinecraftServer} 的 {@code tickTimes} 字段。
 *
 * <h2>这个字段是什么</h2>
 * Minecraft 服务端在主循环里维护一个长度为 100 的环形数组 {@code tickTimes}，
 * 记录<strong>最近 100 次 tick 各自的耗时（单位：纳秒）</strong>。
 * 它就是游戏内 {@code /debug} 和 Spark 等工具计算 MSPT 的原始数据源，
 * 因此比"自己用 System.nanoTime() 夹住 tick 回调"更准确 ——
 * 后者会把 Fabric 事件派发的开销也算进去。
 *
 * <h2>为什么用 Accessor 而不是反射</h2>
 * <ul>
 *   <li>Accessor 在加载期完成校验，字段名不对会立刻报错，而不是运行时静默失败；</li>
 *   <li>零反射开销、可被 JIT 内联；</li>
 *   <li>代码可读，读代码的人一眼能看出取的是什么。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   long[] times = ((MinecraftServerTickTimesAccessor) server).qqlink$tickTimes();
 * </pre>
 *
 * <p>注意：{@code require = 1}（默认值）表示找不到该字段时直接让游戏启动失败。
 * 这样如果你换了 Minecraft 版本而这个字段被改名，会立刻在启动阶段暴露问题，
 * 而不是表现为"#tps 一直没有数据"这种难以排查的现象。
 */
@Mixin(net.minecraft.server.MinecraftServer.class)
public interface MinecraftServerTickTimesAccessor {

    /** 最近 100 次 tick 的耗时（纳秒）。 */
    @Accessor("tickTimes")
    long[] qqlink$tickTimes();
}

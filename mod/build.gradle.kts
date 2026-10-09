// ============================================================
//  qqlink-fabric —— 服务端伴随模组（★可选组件）
//
//  【作用】把代理层拿不到的信息通过插件消息通道 qqlink:main 上报给 Velocity：
//          · TPS / MSPT（#tps 指令的数据来源）
//          · 精确死亡原因
//          · 成就/进度解锁
//          · 服务器启动完成 / 停止
//
//  【不装也能用】聊天转发、加入退出通知、服务器状态、QQ 指令
//          全部由代理端实现，不依赖本模组。
//
//  构建：./gradlew :mod:build     产物：mod/build/libs/qqlink-fabric-1.0.0.jar
// ============================================================
plugins {
    id("fabric-loom") version "1.12-SNAPSHOT"
    java
}

val minecraft_version: String by project
val yarn_mappings: String by project
val loader_version: String by project
val fabric_api_version: String by project
val mod_version: String by project
val maven_group: String by project
val archives_base_name: String by project

version = mod_version
group = maven_group
base.archivesName.set(archives_base_name)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

loom {
    // 用 Mixin Accessor 读取 MinecraftServer#tickTimes（最近 100 次 tick 的耗时数组），
    // 这是 Fabric 生态里获取 MSPT 的标准做法，无需反射、无性能开销。
    // 纯服务端模组，不拆分 client sourceSet。
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings("net.fabricmc:yarn:$yarn_mappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loader_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabric_api_version")
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    jar {
        manifest {
            attributes(mapOf(
                "Implementation-Title" to "qqlink-fabric",
                "Implementation-Version" to project.version
            ))
        }
    }
}

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "QQGroupLink"

// 两个子工程：
//  :proxy  -> Velocity 代理端插件（核心，包含 OneBot 对接与全部互通逻辑）
//  :mod    -> Fabric 服务端伴随模组（可选，把 TPS/MSPT/死亡/成就等信息通过插件消息上报给代理）
include("proxy")
include("mod")

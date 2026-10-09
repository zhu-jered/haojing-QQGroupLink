// 聚合构建脚本：定义全局版本号，子工程各自声明依赖。
plugins {
    java
}

allprojects {
    group = "com.qqlink"
    version = "1.0.0"

    repositories {
        // Fabric / Minecraft 官方库
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://libraries.minecraft.net/") { name = "Minecraft" }
        // Velocity、Adventure 等 PaperMC 产物
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        // 兜底公共仓库
        mavenCentral()
        maven("https://repo1.maven.org/maven2/") { name = "MavenCentralMirror" }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}

subprojects {
    apply(plugin = "java")
}

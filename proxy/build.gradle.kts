// ============================================================
//  Velocity 代理端插件 —— QQ 群服互通（QQGroupLink）
//  产物：proxy/build/libs/QQGroupLink-1.0.0.jar
//  运行要求：JDK 21 / Velocity 3.4.x
// ============================================================
plugins {
    java
}

val velocity_version: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Velocity 提供全部运行期依赖（Gson / Guava / Adventure / SLF4J 等），
    // 因此这里必须使用 compileOnly，最终 jar 内不打包任何第三方库 —— 满足"轻量、最少依赖"要求。
    compileOnly("com.velocitypowered:velocity-api:$velocity_version")
    // 注解处理器：编译期读取 @Plugin 注解并生成 velocity-plugin.json
    annotationProcessor("com.velocitypowered:velocity-api:$velocity_version")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // 把 src/main/resources 一并打进 jar（velocity-plugin.json + 默认 config.yml 模板）
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    jar {
        archiveBaseName.set("QQGroupLink")
        archiveVersion.set(project.version.toString())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes(
                "Implementation-Title" to "QQGroupLink",
                "Implementation-Version" to project.version,
                "Specification-Vendor" to "qqlink"
            )
        }
    }
}

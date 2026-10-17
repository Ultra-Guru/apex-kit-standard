import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    // 零 Android 依赖：纯 Kotlin JVM 库（协程之外不引任何运行时依赖——
    // 引擎内的 JSON 处理刻意走纯字符串提取，保持坐标极简、消费面最广）
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.standardkit"
version = "1.1.1"

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.None(), sourcesJar = true))
    coordinates(
        groupId = "com.apex.standardkit",
        artifactId = "standard-loop",
        version = "1.1.0",
    )
    pom {
        name.set("Apex Standard Kit :: Standard Loop")
        description.set(
            "Pure-Kotlin agent task-loop engine: profiles, permission gate, isolated " +
                "sub-agents, context compaction, provider-safe tool surface and MCP " +
                "guidance injection for mobile coding workspaces (zero Android deps)"
        )
        url.set("https://github.com/Ultra-Guru/apex-standard-kit")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("AceGuru-mjh") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-standard-kit.git")
            url.set("https://github.com/Ultra-Guru/apex-standard-kit")
        }
    }
}

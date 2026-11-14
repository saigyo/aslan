import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugins("org.jetbrains.plugins.yaml", "com.intellij.modules.json", "intellij.structureView.plugin")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks.test {
    systemProperty("aslan.privateFixtures", providers.gradleProperty("aslan.privateFixtures").getOrElse(""))
}

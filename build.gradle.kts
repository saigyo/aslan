import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // The layered layout algorithm of Eclipse ELK, bundled unmodified with EMF; Guava comes from the IDE
    // (module intellij.libraries.guava).
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.12.1") { exclude(group = "com.google.guava") }

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

// The licence and the notice of the repository go into the plugin's jar, where the Apache License asks for them.
tasks.processResources {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

tasks.test {
    timeout.set(Duration.ofMinutes(30))
    systemProperty("aslan.privateFixtures", providers.gradleProperty("aslan.privateFixtures").getOrElse(""))
}

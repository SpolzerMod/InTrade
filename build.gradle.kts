plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
}

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.rosewooddev.io/repository/public/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:-processing"))
    }
}

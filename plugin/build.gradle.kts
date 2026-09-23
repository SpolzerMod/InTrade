plugins {
    id("com.gradleup.shadow")
}

description = "Player-to-player trading for Paper"

val latestApi: Configuration by configurations.creating

dependencies {
    implementation(project(":api"))
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    compileOnly("org.black_ixx:playerpoints:3.3.5") { isTransitive = false }
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }
    compileOnly("com.zaxxer:HikariCP:7.1.0") { isTransitive = false }

    latestApi("io.papermc.paper:paper-api:26.3.build.34-alpha")
    latestApi("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    latestApi("org.black_ixx:playerpoints:3.3.5") { isTransitive = false }
    latestApi("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    latestApi("me.clip:placeholderapi:2.11.6") { isTransitive = false }
    latestApi("com.zaxxer:HikariCP:7.1.0") { isTransitive = false }
    latestApi("org.jetbrains:annotations:26.0.2")
}

// The plugin is compiled against 1.21.7, the oldest supported version.
// This task compiles it against the latest Paper API to catch removed methods.
val verifyLatestApi by tasks.registering(JavaCompile::class) {
    source = sourceSets.main.get().java
    classpath = latestApi + project(":api").sourceSets.main.get().output
    destinationDirectory.set(layout.buildDirectory.dir("verify-latest"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.check {
    dependsOn(verifyLatestApi)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("InTrade")
    archiveClassifier.set("")
    manifest.attributes(
        "Implementation-Title" to "InTrade",
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "Spolzer"
    )
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

plugins {
    java
}

group = "io.github.d3vx3n0"
version = "0.1.0-SNAPSHOT"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net/") {
        name = "mojang"
        content { includeGroup("com.mojang") }
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
        content {
            includeGroup("io.papermc.paper")
            includeGroup("net.md-5")
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val paperApi = "io.papermc.paper:paper-api:26.3.build.248-stable"

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    compileOnly(paperApi)
    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    }
    from("LICENSE") { into("META-INF") }
    manifest.attributes("Implementation-Version" to project.version)
}

tasks.test {
    useJUnitPlatform { excludeTags("database") }
    dependsOn(tasks.jar)
    systemProperty("chronicle.artifact", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "File-backed SQLite, writer failure and process-crash checks."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("database") }
    dependsOn(tasks.jar)
    systemProperty("chronicle.artifact", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("chronicle.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.register<JavaExec>("historyLoadTest") {
    group = "verification"
    description = "Opt-in deterministic SQLite index/mixed-load measurements in disposable databases."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.d3vx3n0.chronicle.storage.HistoryLoad")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "512m"
    args(layout.buildDirectory.dir("load-tests").get().asFile.absolutePath,
        providers.gradleProperty("historySizes").orElse("10000,100000,1000000").get())
}

// The diagnostic fixture is deliberately excluded from the distributable plugin.
val paperTestPlugin = sourceSets.create("paperTestPlugin")
configurations[paperTestPlugin.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
dependencies {
    testImplementation(paperTestPlugin.output)
}

val paperTestPluginJar = tasks.register<Jar>("paperTestPluginJar") {
    group = "verification"
    description = "Build the separate Paper event-observation fixture (never install on production)."
    archiveClassifier.set("paper-test-plugin")
    from(paperTestPlugin.output)
    from("LICENSE") { into("META-INF") }
}

val paperIntegration = sourceSets.create("paperIntegration")
dependencies {
    testImplementation(paperIntegration.output)
}

tasks.register<JavaExec>("paperIntegrationTest") {
    group = "verification"
    description = "Run the packaged plugin in a disposable Paper server; requires explicit EULA acceptance."
    dependsOn(tasks.jar, paperTestPluginJar, paperIntegration.classesTaskName)
    classpath = paperIntegration.runtimeClasspath
    mainClass.set("io.github.d3vx3n0.chronicle.harness.PaperSmokeTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(
        layout.projectDirectory.file("gradle/paper-test.properties").asFile.absolutePath,
        layout.buildDirectory.dir("paper-tests").get().asFile.absolutePath,
        tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath,
        paperTestPluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
        providers.gradleProperty("acceptMinecraftEula").orElse("false").get()
    )
}

// Compile all fixture/harness sources in normal CI without downloading or starting Minecraft.
tasks.register<JavaExec>("paperPacketTest") {
    group = "verification"
    description = "Qualify actual loopback player packets on pinned Paper; requires explicit EULA acceptance."
    dependsOn(tasks.jar, paperTestPluginJar, paperIntegration.classesTaskName)
    classpath = paperIntegration.runtimeClasspath
    mainClass.set("io.github.d3vx3n0.chronicle.harness.PaperPacketTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(
        layout.projectDirectory.file("gradle/paper-test.properties").asFile.absolutePath,
        layout.buildDirectory.dir("paper-tests").get().asFile.absolutePath,
        tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath,
        paperTestPluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
        providers.gradleProperty("acceptMinecraftEula").orElse("false").get()
    )
}

tasks.check {
    dependsOn(paperTestPluginJar, paperIntegration.classesTaskName, integrationTest)
}

plugins {
    java
}

repositories {
    // Deliberately no mavenLocal(): a stale ~/.m2 copy of a module shadows Maven Central for
    // every one of its artifacts, which breaks resolution of platform-specific natives
    // classifiers that were added to the RuneLite client after the local copy was cached.
    maven {
        url = uri("https://repo.runelite.net")
        content {
            includeGroupByRegex("net\\.runelite.*")
        }
    }
    mavenCentral()
}

dependencies {
    val lombokVersion = "1.18.30" // supports JDK 21 and is verified by runelite
    // old annotation processor approach due to runelite plugin hub verification restrictions
    compileOnly(group = "org.projectlombok", name = "lombok", version = lombokVersion)
    annotationProcessor(group = "org.projectlombok", name = "lombok", version = lombokVersion)
    testCompileOnly(group = "org.projectlombok", name = "lombok", version = lombokVersion)
    testAnnotationProcessor(group = "org.projectlombok", name = "lombok", version = lombokVersion)

    // this version of annotations is verified by runelite
    compileOnly(group = "org.jetbrains", name = "annotations", version = "23.0.0")
    testCompileOnly(group = "org.jetbrains", name = "annotations", version = "23.0.0")

    val runeLiteVersion = "latest." + if (project.hasProperty("use.snapshot")) "integration" else "release"
    compileOnly(group = "net.runelite", name = "client", version = runeLiteVersion)
    testImplementation(group = "net.runelite", name = "client", version = runeLiteVersion)

    val junitVersion = "5.5.2" // max version before junit-bom was added to pom files, due to runelite restrictions
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-api", version = junitVersion)
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-params", version = junitVersion)
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-engine", version = junitVersion)
    testRuntimeOnly(group = "org.junit.platform", name = "junit-platform-launcher", version = "1.5.2")

    // mocking and test injection used by runelite client
    testImplementation(group = "org.mockito", name = "mockito-core", version = "4.11.0") // runelite uses 3.1.0
    testImplementation(group = "com.google.inject.extensions", name = "guice-testlib", version = "4.1.0") {
        exclude(group = "com.google.inject", module = "guice") // already provided by runelite client
    }

    // okhttp is provided by the runelite client at runtime; mockwebserver is test-only
    testImplementation(group = "com.squareup.okhttp3", name = "mockwebserver", version = "3.14.9")
}

group = "dinkbingo"
version = "1.0.0"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    val version = JavaVersion.VERSION_11.toString()
    sourceCompatibility = version
    targetCompatibility = version
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val pluginMainClass = "dinkbingo.BingoPluginTest"

tasks.register(name = "run", type = JavaExec::class) {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set(pluginMainClass)

    jvmArgs(
        "-ea",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"
    )

    args(
        "--developer-mode",
        "--debug"
    )
}

// Prints the runtime classpath, for launching the dev client by hand without Gradle.
tasks.register("printClasspath") {
    doLast { println(sourceSets.test.get().runtimeClasspath.asPath) }
}

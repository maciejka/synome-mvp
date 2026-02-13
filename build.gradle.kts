plugins {
    java
    alias(libs.plugins.quarkus)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.dependencycheck)
    checkstyle
    pmd
    jacoco
}

repositories {
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy {
        force(
            "org.testcontainers:testcontainers:1.21.2",
            "org.testcontainers:jdbc:1.21.2",
            "org.testcontainers:database-commons:1.21.2",
            "org.testcontainers:postgresql:1.21.2",
            "com.github.docker-java:docker-java-api:3.5.0",
            "com.github.docker-java:docker-java-transport:3.5.0",
            "com.github.docker-java:docker-java-transport-zerodep:3.5.0",
        )
    }
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.logging.json)
    implementation(libs.quarkus.scheduler)
    implementation(libs.flyway.postgresql)
    implementation(libs.jooq)
    implementation(libs.drools.engine)
    implementation(libs.drools.mvel)
    implementation(libs.lz4.java)
    implementation(libs.jackson.dataformat.msgpack)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    testImplementation(libs.testcontainers.postgresql)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

// --- Code Quality ---

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "10.21.4"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.withType<Checkstyle> {
    // Only check hand-written source, skip Quarkus-generated source sets
    enabled = name == "checkstyleMain" || name == "checkstyleTest"
    source = fileTree("src/main/java") + fileTree("src/test/java")
}

spotbugs {
    effort = com.github.spotbugs.snom.Effort.DEFAULT
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    excludeFilter = file("config/spotbugs/exclude-filter.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    // Only check hand-written source, skip Quarkus-generated source sets
    enabled = name == "spotbugsMain" || name == "spotbugsTest"
    reports.create("html") { required = true }
    reports.create("xml") { required = false }
}

// SpotBugs main picks up Quarkus-generated class dirs as auxiliary paths;
// declare the implicit dependency so Gradle doesn't complain
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    dependsOn("compileQuarkusGeneratedSourcesJava")
}

// PMD complexity checks
pmd {
    toolVersion = "6.55.0"
    isIgnoreFailures = false
    ruleSets = listOf()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

tasks.withType<Pmd> {
    // Only check hand-written source, skip Quarkus-generated source sets
    enabled = name == "pmdMain" || name == "pmdTest"
    source = if (name == "pmdMain") fileTree("src/main/java") else fileTree("src/test/java")
    reports {
        html.required.set(true)
        xml.required.set(false)
    }
}

val cpdMinimumTokens = 100

fun registerCpdTask(taskName: String, sourceRoot: String) {
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Runs PMD CPD duplication checks for $sourceRoot."
        classpath = configurations.named("pmd").get()
        mainClass.set("net.sourceforge.pmd.cpd.CPD")
        args(
            "--minimum-tokens", cpdMinimumTokens.toString(),
            "--language", "java",
            "--format", "text",
            "--fail-on-violation", "true",
            "--dir", sourceRoot,
        )
        inputs.files(fileTree(sourceRoot) { include("**/*.java") })
    }
}

registerCpdTask("cpdMain", "src/main/java")
registerCpdTask("cpdTest", "src/test/java")

dependencyCheck {
    failBuildOnCVSS = 7.0F
    formats = listOf("HTML", "JSON")

    analyzers.apply {
        assemblyEnabled = false
    }

    val nvdApiKey = System.getenv("NVD_API_KEY")
    if (!nvdApiKey.isNullOrBlank()) {
        nvd.apiKey = nvdApiKey
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // Jacoco scans class directories that include Quarkus-generated output.
    // Declare this dependency explicitly to satisfy Gradle task validation.
    dependsOn("compileQuarkusGeneratedSourcesJava")
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.test {
    // Keep Testcontainers compatible with newer local Docker daemon minimum API versions.
    environment("DOCKER_API_VERSION", "1.44")
    systemProperty("docker.api.version", "1.44")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register("qa") {
    group = "verification"
    description = "Runs the core QA gate: formatting, static analysis, tests, and coverage."
    dependsOn(
        "spotlessCheck",
        "checkstyleMain",
        "checkstyleTest",
        "spotbugsMain",
        "spotbugsTest",
        "pmdMain",
        "pmdTest",
        "cpdMain",
        "cpdTest",
        "test",
    )
}

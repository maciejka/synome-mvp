plugins {
    java
    alias(libs.plugins.quarkus)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    checkstyle
    jacoco
}

repositories {
    mavenCentral()
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
    implementation(libs.flyway.postgresql)
    implementation(libs.jooq)
    implementation(libs.drools.engine)
    implementation(libs.drools.mvel)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
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

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

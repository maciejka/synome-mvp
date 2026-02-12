plugins {
    java
    alias(libs.plugins.quarkus)
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

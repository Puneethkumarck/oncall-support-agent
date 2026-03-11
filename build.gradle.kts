plugins {
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.3.0"
    java
    `java-test-fixtures`
    jacoco
}

val javaVersion: String by project
val archunitVersion: String by project
val wiremockVersion: String by project
val embabelVersion: String by project
val lombokVersion: String by project

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion.toInt())
    }
}

repositories {
    mavenCentral()
}

// Integration test source set
val integrationTestSourceSet: SourceSet = sourceSets.create("integrationTest") {
    java.srcDir("src/integration-test/java")
    resources.srcDir("src/integration-test/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations {
    named("integrationTestImplementation") {
        extendsFrom(configurations.testImplementation.get())
    }
    named("integrationTestRuntimeOnly") {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    configure<JacocoTaskExtension> { isEnabled = false }
}

dependencies {
    // Embabel Agent
    implementation("com.embabel.agent:embabel-agent-starter:$embabelVersion")
    implementation("com.embabel.agent:embabel-agent-starter-shell:$embabelVersion")

    // LLM provider for Embabel GOAP agent (Ollama — local, free)
    implementation("com.embabel.agent:embabel-agent-starter-ollama:$embabelVersion")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Lombok
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // Test fixtures
    testFixturesImplementation("org.assertj:assertj-core")
    testFixturesImplementation("org.mockito:mockito-core")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("com.embabel.agent:embabel-agent-test:$embabelVersion")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")

    // Integration test
    "integrationTestImplementation"(testFixtures(project))
    "integrationTestImplementation"("com.embabel.agent:embabel-agent-test:$embabelVersion")
}

spotless {
    java {
        removeUnusedImports()
        importOrder("", "java|javax", "\\#")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> {
    jvmArgs("-Dnet.bytebuddy.experimental=true")
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

jacoco { toolVersion = "0.8.14" }

tasks.test {
    configure<JacocoTaskExtension> {
        excludes = listOf("sun.*", "jdk.*", "com.sun.*", "java.*", "javax.*")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val jacocoExclusions = listOf(
    "**/config/**",
    "**/*Application*",
    "**/generated/**"
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(jacocoExclusions) }
    }))
}

tasks.named("check") {
    dependsOn(tasks.named("integrationTest"))
}

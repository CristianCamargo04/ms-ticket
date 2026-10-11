plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "com.ticketing"
version = "1.0.0"
description = "Reactive ticketing event processing platform"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val mockitoAgent: Configuration = configurations.create("mockitoAgent")

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.aws.sdk.bom))
    implementation(platform(libs.jackson.bom))
    annotationProcessor(platform(libs.spring.boot.bom))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.caffeine)

    implementation(libs.aws.dynamodb)
    implementation(libs.aws.dynamodb.enhanced)
    implementation(libs.aws.sqs)
    implementation(libs.aws.netty.nio.client)
    implementation(libs.aws.sts)

    implementation(libs.resilience4j.reactor)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.micrometer)
    implementation(libs.bucket4j.core)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    mockitoAgent(platform(libs.spring.boot.bom))
    mockitoAgent(libs.mockito.core) { isTransitive = false }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                compileOnly(libs.lombok)
                annotationProcessor(libs.lombok)
                implementation(sourceSets.main.get().output)
                implementation(platform(libs.spring.boot.bom))
                implementation(platform(libs.aws.sdk.bom))
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.boot.starter.webflux.test)
                implementation(libs.spring.boot.starter.security.test)
                implementation(libs.spring.boot.testcontainers)
                implementation(libs.reactor.test)
                implementation(libs.testcontainers.junit)
                implementation(libs.testcontainers.localstack)
                implementation(libs.awaitility)
                implementation(libs.aws.dynamodb)
                implementation(libs.aws.dynamodb.enhanced)
                implementation(libs.aws.sqs)
                implementation(libs.spring.boot.starter.webflux)
                implementation(libs.spring.boot.starter.security)
                implementation(libs.spring.boot.starter.oauth2.resource.server)
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-javaagent:${mockitoAgent.asPath}", "-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverageExclusions = listOf(
    "com/ticketing/TicketingApplication*",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
    dependsOn(tasks.named("integrationTest"))
}

tasks.bootJar {
    archiveFileName = "ms-ticket.jar"
}

tasks.jar {
    enabled = false
}

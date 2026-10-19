plugins {
    java
    id("io.grafana.pyroscope.source-mapper")
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:4.1.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.google.guava:guava:33.7.2-jre")
    implementation("ch.qos.logback:logback-classic:1.6.5")
}

pyroscopeSourceMapper {
    outputFile = ".pyroscope.yaml"
    includeConfigs = listOf("runtimeClasspath")
    includeLocalProject = true
    language = "java"
}

tasks.register("buildWithSourceMap") {
    dependsOn("generatePyroscopeSourceMap", "build")
}

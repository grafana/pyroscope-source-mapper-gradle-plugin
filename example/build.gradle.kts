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
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.google.guava:guava:32.1.3-jre")
    implementation("ch.qos.logback:logback-classic:1.4.14")
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

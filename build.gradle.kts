plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.grafana.pyroscope"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("org.yaml:snakeyaml:2.2")
    
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("pyroscopeSourceMapper") {
            id = "io.grafana.pyroscope.source-mapper"
            implementationClass = "io.grafana.pyroscope.mapper.PyroscopeSourceMapperPlugin"
            displayName = "Pyroscope Source Mapper"
            description = "Generates .pyroscope.yaml files mapping dependencies to source repositories"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
}

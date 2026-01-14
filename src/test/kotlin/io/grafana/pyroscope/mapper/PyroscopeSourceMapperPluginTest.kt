package io.grafana.pyroscope.mapper

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PyroscopeSourceMapperPluginTest {
    
    @TempDir
    lateinit var testProjectDir: File
    
    @Test
    fun `plugin applies successfully`() {
        val buildFile = testProjectDir.resolve("build.gradle.kts")
        buildFile.writeText("""
            plugins {
                id("java")
                id("io.grafana.pyroscope.source-mapper")
            }
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                implementation("com.google.guava:guava:32.1.3-jre")
            }
        """.trimIndent())
        
        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--all")
            .withPluginClasspath()
            .build()
        
        assertTrue(result.output.contains("generatePyroscopeSourceMap"))
    }
    
    @Test
    fun `task generates pyroscope yaml file`() {
        val buildFile = testProjectDir.resolve("build.gradle.kts")
        buildFile.writeText("""
            plugins {
                id("java")
                id("io.grafana.pyroscope.source-mapper")
            }
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                implementation("com.google.guava:guava:32.1.3-jre")
            }
            
            pyroscopeSourceMapper {
                includeLocalProject = false
            }
        """.trimIndent())
        
        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("generatePyroscopeSourceMap", "--stacktrace")
            .withPluginClasspath()
            .build()
        
        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePyroscopeSourceMap")?.outcome)
        
        val pyroscopeFile = testProjectDir.resolve(".pyroscope.yaml")
        assertTrue(pyroscopeFile.exists(), "Generated .pyroscope.yaml file should exist")
        
        val content = pyroscopeFile.readText()
        assertTrue(content.contains("version: v1"))
        assertTrue(content.contains("source_code:"))
        assertTrue(content.contains("mappings:"))
    }
    
    @Test
    fun `custom output file location`() {
        val buildFile = testProjectDir.resolve("build.gradle.kts")
        buildFile.writeText("""
            plugins {
                id("java")
                id("io.grafana.pyroscope.source-mapper")
            }
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.9")
            }
            
            pyroscopeSourceMapper {
                outputFile = "build/pyroscope-custom.yaml"
                includeLocalProject = false
            }
        """.trimIndent())
        
        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("generatePyroscopeSourceMap")
            .withPluginClasspath()
            .build()
        
        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePyroscopeSourceMap")?.outcome)
        
        val customFile = testProjectDir.resolve("build/pyroscope-custom.yaml")
        assertTrue(customFile.exists(), "Custom output file should exist")
    }
}

/*
 * Copyright 2026 Grafana Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.grafana.pyroscope.mapper

import io.grafana.pyroscope.mapper.generator.YamlGenerator
import io.grafana.pyroscope.mapper.model.*
import io.grafana.pyroscope.mapper.resolver.DependencyResolver
import io.grafana.pyroscope.mapper.resolver.MetadataCache
import io.grafana.pyroscope.mapper.resolver.SourceLocationResolver
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class PyroscopeSourceMapperTask : DefaultTask() {
    
    @get:OutputFile
    abstract val outputFile: RegularFileProperty
    
    @get:Input
    abstract val includeConfigs: ListProperty<String>
    
    @get:Input
    abstract val skipConfigs: ListProperty<String>
    
    @get:Input
    abstract val includeLocalProject: Property<Boolean>

    @get:Input
    abstract val includeJavaStdlib: Property<Boolean>

    @get:Input
    abstract val language: Property<String>
    
    @get:Input
    abstract val version: Property<String>
    
    @get:Input
    abstract val useMavenCentralMetadata: Property<Boolean>
    
    @get:Input
    abstract val customMappings: MapProperty<String, CustomSourceMapping>
    
    @get:OutputDirectory
    abstract val cacheDir: DirectoryProperty
    
    @TaskAction
    fun generate() {
        val outputFileValue = outputFile.get().asFile
        val cacheDirValue = cacheDir.get().asFile
        
        logger.lifecycle("Generating Pyroscope source map: ${outputFileValue.absolutePath}")
        
        // Initialize cache
        val metadataCache = MetadataCache(cacheDirValue.resolve("metadata-cache.bin"))
        
        // Initialize GitHub source analyzer
        val analyzerCacheDir = cacheDirValue.resolve("github-archives")
        val githubSourceAnalyzer = io.grafana.pyroscope.mapper.resolver.GitHubSourceAnalyzer(logger, analyzerCacheDir)
        
        // Resolve dependencies
        val dependencyResolver = DependencyResolver(project, logger)
        val dependencies = dependencyResolver.resolveDependencies(
            includeConfigs.get(),
            skipConfigs.get()
        )
        
        logger.info("Found ${dependencies.size} dependencies to map")
        
        // Resolve source locations
        val sourceLocationResolver = SourceLocationResolver(project, logger, metadataCache, githubSourceAnalyzer)
        val mappings = mutableListOf<SourceMapping>()
        
        // Add local project mapping if requested
        if (includeLocalProject.get()) {
            val localMapping = createLocalProjectMapping()
            if (localMapping != null) {
                mappings.add(localMapping)
                logger.info("Added local project mapping")
            }
        }

        // Add Java stdlib mapping if requested
        if (includeJavaStdlib.get()) {
            val stdlibMapping = createJavaStdlibMapping()
            mappings.add(stdlibMapping)
            logger.info("Added Java stdlib mapping to OpenJDK source")
        }

        // Process each dependency
        var resolvedCount = 0
        dependencies.forEach { dependency ->
            // Skip dependencies with no packages
            // This includes:
            // - BOM (Bill of Materials) dependencies (e.g., jackson-bom, spring-boot-dependencies)
            // - Starter/aggregator POMs (e.g., spring-boot-starter-*)
            // - Empty placeholder artifacts (e.g., guava listenablefuture)
            if (dependency.packages.isEmpty()) {
                logger.debug("Skipping ${dependency.groupId}:${dependency.artifactId} - no packages found (likely BOM/POM-only artifact)")
                return@forEach
            }
            
            val source = sourceLocationResolver.resolveSourceLocation(
                dependency,
                customMappings.get(),
                useMavenCentralMetadata.get()
            )
            
            if (source != null) {
                val mapping = SourceMapping(
                    functionName = dependency.packages.map { packagePath ->
                        SourceMapping.FunctionPrefix(packagePath)
                    },
                    language = language.get(),
                    source = source
                )
                mappings.add(mapping)
                resolvedCount++
            }
        }
        
        logger.lifecycle("Resolved source locations for $resolvedCount out of ${dependencies.size} dependencies")
        
        // Generate YAML
        val config = PyroscopeConfig(
            version = version.get(),
            sourceCode = PyroscopeConfig.SourceCode(mappings)
        )
        
        val generator = YamlGenerator()
        generator.generate(config, outputFileValue)
        
        // Save cache
        metadataCache.save()
        
        logger.lifecycle("Successfully generated ${outputFileValue.name} with ${mappings.size} mappings")
    }
    
    /**
     * Create mapping for the local project source code
     */
    private fun createLocalProjectMapping(): SourceMapping? {
        val sourceSet = project.extensions.findByName("sourceSets") as? org.gradle.api.tasks.SourceSetContainer
        val mainSourceSet = sourceSet?.findByName("main")
        
        val javaDirs = mainSourceSet?.java?.srcDirs?.filter { it.exists() } ?: emptyList()
        
        if (javaDirs.isEmpty()) {
            logger.warn("No source directories found for local project")
            return null
        }
        
        // Get all packages from source directories
        val packages = mutableSetOf<String>()
        javaDirs.forEach { srcDir ->
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    val relativePath = file.relativeTo(srcDir).path
                    val packagePath = relativePath.substringBeforeLast('/').replace('/', '.')
                    if (packagePath.isNotEmpty()) {
                        packages.add(packagePath.replace('.', '/'))
                    }
                }
        }
        
        if (packages.isEmpty()) {
            logger.warn("No packages found in local project")
            return null
        }
        
        // Use the first source directory as the local path (relative to project)
        val srcDir = javaDirs.first()
        val localPath = srcDir.relativeTo(project.projectDir).path
        
        return SourceMapping(
            functionName = packages.map { packagePath ->
                SourceMapping.FunctionPrefix(packagePath)
            },
            language = language.get(),
            source = SourceMapping.Source(
                local = LocalSource(localPath)
            )
        )
    }

    /**
     * Create mapping for Java standard library packages to OpenJDK source
     */
    private fun createJavaStdlibMapping(): SourceMapping {
        // Common Java stdlib package prefixes
        // Note: This covers the most commonly used packages from various Java modules
        // Users can override these with custom mappings if needed for specific versions
        val stdlibPackages = listOf(
            // java.base module - core Java packages
            "java/lang",
            "java/util",
            "java/io",
            "java/nio",
            "java/net",
            "java/math",
            "java/time",
            "java/security",
            "java/text",
            "javax/crypto",
            "javax/net",
            "javax/security",
            // java.sql module
            "java/sql",
            "javax/sql",
            // java.desktop module
            "java/awt",
            "java/beans",
            "javax/swing",
            "javax/imageio",
            "javax/print",
            "javax/sound",
            "javax/accessibility",
            // java.rmi module
            "java/rmi",
            // java.naming module
            "javax/naming",
            // java.management module
            "javax/management",
            // java.xml module
            "javax/xml",
            // java.transaction.xa module
            "javax/transaction",
            // JDK internal packages
            "jdk/internal",
            // Sun proprietary packages (internal APIs)
            "sun/misc",
            "sun/nio",
            "sun/reflect",
            "sun/security",
            "sun/util",
            "sun/net",
            "sun/font",
            "sun/awt",
            "sun/swing",
            "sun/print",
            "sun/text",
            // com.sun packages (internal APIs)
            "com/sun",
            // Additional javax packages
            "javax/annotation",  // java.compiler module
            "javax/tools",       // java.compiler module
            "javax/script"       // java.scripting module
        )

        // Detect Java version and map to appropriate OpenJDK tag
        val javaVersion = getJavaVersion()
        val jdkRef = "jdk-$javaVersion+0"

        logger.debug("Using OpenJDK tag: $jdkRef for Java version $javaVersion")

        return SourceMapping(
            functionName = stdlibPackages.map { packagePath ->
                SourceMapping.FunctionPrefix(packagePath)
            },
            language = language.get(),
            source = SourceMapping.Source(
                github = GitHubSource(
                    owner = "openjdk",
                    repo = "jdk",
                    ref = jdkRef,
                    path = "src/java.base/share/classes"
                )
            )
        )
    }

    /**
     * Get the Java version used by the project
     */
    private fun getJavaVersion(): String {
        // Try to get from Java toolchain if configured
        val javaExtension = project.extensions.findByName("java") as? org.gradle.api.plugins.JavaPluginExtension
        if (javaExtension != null) {
            try {
                // Try to get toolchain version
                val toolchain = javaExtension.toolchain
                val languageVersion = toolchain.languageVersion.orNull
                if (languageVersion != null) {
                    val version = languageVersion.asInt()
                    logger.debug("Detected Java version from toolchain: $version")
                    return version.toString()
                }
            } catch (e: Exception) {
                logger.debug("Could not detect Java version from toolchain: ${e.message}")
            }

            // Fall back to target compatibility
            try {
                val targetCompatibility = javaExtension.targetCompatibility
                val version = targetCompatibility.majorVersion
                logger.debug("Detected Java version from targetCompatibility: $version")
                return version
            } catch (e: Exception) {
                logger.debug("Could not detect Java version from targetCompatibility: ${e.message}")
            }
        }

        // Fall back to runtime Java version
        val runtimeVersion = System.getProperty("java.version")
        val majorVersion = runtimeVersion.split(".").first().toIntOrNull() ?: 17
        logger.debug("Using runtime Java version: $majorVersion")
        return majorVersion.toString()
    }
}

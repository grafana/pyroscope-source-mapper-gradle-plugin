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
        
        // Resolve dependencies
        val dependencyResolver = DependencyResolver(project, logger)
        val dependencies = dependencyResolver.resolveDependencies(
            includeConfigs.get(),
            skipConfigs.get()
        )
        
        logger.info("Found ${dependencies.size} dependencies to map")
        
        // Resolve source locations
        val sourceLocationResolver = SourceLocationResolver(project, logger, metadataCache)
        val mappings = mutableListOf<SourceMapping>()
        
        // Add local project mapping if requested
        if (includeLocalProject.get()) {
            val localMapping = createLocalProjectMapping()
            if (localMapping != null) {
                mappings.add(localMapping)
                logger.info("Added local project mapping")
            }
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
                        SourceMapping.FunctionPrefix(listOf(packagePath))
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
                SourceMapping.FunctionPrefix(listOf(packagePath))
            },
            language = language.get(),
            source = SourceMapping.Source(
                local = LocalSource(localPath)
            )
        )
    }
}

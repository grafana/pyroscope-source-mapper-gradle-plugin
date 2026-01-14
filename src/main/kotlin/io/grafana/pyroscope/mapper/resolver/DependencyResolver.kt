package io.grafana.pyroscope.mapper.resolver

import io.grafana.pyroscope.mapper.model.DependencyInfo
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import java.io.File
import java.util.jar.JarFile
import org.gradle.api.logging.Logger

class DependencyResolver(
    private val project: Project,
    private val logger: Logger
) {
    /**
     * Resolve all dependencies from specified configurations
     */
    fun resolveDependencies(
        includeConfigs: List<String>,
        skipConfigs: List<String>
    ): List<DependencyInfo> {
        val configsToProcess = findMatchingConfigurations(includeConfigs, skipConfigs)
        
        logger.info("Processing configurations: ${configsToProcess.joinToString(", ")}")
        
        val dependenciesMap = mutableMapOf<String, DependencyInfo>()
        
        configsToProcess.forEach { configName ->
            val configuration = project.configurations.findByName(configName)
            
            if (configuration == null) {
                logger.warn("Configuration '$configName' not found")
                return@forEach
            }
            
            if (!configuration.isCanBeResolved) {
                logger.debug("Configuration '$configName' cannot be resolved, skipping")
                return@forEach
            }
            
            try {
                val resolvedConfig = configuration.resolvedConfiguration
                
                resolvedConfig.firstLevelModuleDependencies.forEach { dependency ->
                    processDependency(dependency, dependenciesMap)
                }
            } catch (e: Exception) {
                logger.warn("Failed to resolve configuration '$configName': ${e.message}")
            }
        }
        
        logger.info("Resolved ${dependenciesMap.size} unique dependencies")
        return dependenciesMap.values.toList()
    }
    
    /**
     * Find configurations matching include/skip patterns
     */
    private fun findMatchingConfigurations(
        includeConfigs: List<String>,
        skipConfigs: List<String>
    ): Set<String> {
        val allConfigs = project.configurations.names
        val skipPatterns = skipConfigs.map { it.toRegex() }
        
        return includeConfigs.flatMap { includePattern ->
            if (includePattern.contains("*") || includePattern.contains("?")) {
                val regex = includePattern.toRegex()
                allConfigs.filter { it.matches(regex) }
            } else {
                listOf(includePattern)
            }
        }.filter { configName ->
            skipPatterns.none { pattern -> configName.matches(pattern) }
        }.toSet()
    }
    
    /**
     * Process a dependency and all its transitive dependencies
     */
    private fun processDependency(
        dependency: ResolvedDependency,
        dependenciesMap: MutableMap<String, DependencyInfo>
    ) {
        val key = "${dependency.moduleGroup}:${dependency.moduleName}:${dependency.moduleVersion}"
        
        if (dependenciesMap.containsKey(key)) {
            return
        }
        
        try {
            val packages = extractPackagesFromArtifacts(dependency.moduleArtifacts)
            
            val depInfo = DependencyInfo(
                groupId = dependency.moduleGroup,
                artifactId = dependency.moduleName,
                version = dependency.moduleVersion,
                packages = packages
            )
            
            dependenciesMap[key] = depInfo
            
            logger.debug("Processed dependency: $key with ${packages.size} packages")
            
            // Process transitive dependencies
            dependency.children.forEach { child ->
                processDependency(child, dependenciesMap)
            }
        } catch (e: Exception) {
            logger.warn("Failed to process dependency $key: ${e.message}")
        }
    }
    
    /**
     * Extract package prefixes from JAR artifacts
     */
    private fun extractPackagesFromArtifacts(artifacts: Set<ResolvedArtifact>): Set<String> {
        val packages = mutableSetOf<String>()
        
        artifacts.forEach { artifact ->
            val file = artifact.file
            if (file.extension == "jar") {
                packages.addAll(extractPackagesFromJar(file))
            }
        }
        
        return packages
    }
    
    /**
     * Extract package names from JAR file
     */
    private fun extractPackagesFromJar(jarFile: File): Set<String> {
        val packages = mutableSetOf<String>()
        
        try {
            JarFile(jarFile).use { jar ->
                jar.entries().asIterator().forEach { entry ->
                    if (entry.name.endsWith(".class") && !entry.isDirectory) {
                        val packagePath = entry.name.substringBeforeLast('/')
                        if (packagePath.isNotEmpty()) {
                            // Convert path separator to package notation
                            packages.add(packagePath)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to read JAR file ${jarFile.name}: ${e.message}")
        }
        
        return packages
    }
}

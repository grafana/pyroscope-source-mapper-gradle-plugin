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
package io.grafana.pyroscope.mapper.resolver

import io.grafana.pyroscope.mapper.model.*
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SourceLocationResolver(
    private val project: Project,
    private val logger: Logger,
    private val metadataCache: MetadataCache,
    private val githubSourceAnalyzer: GitHubSourceAnalyzer
) {
    /**
     * Resolve source location for a dependency
     */
    fun resolveSourceLocation(
        dependencyInfo: DependencyInfo,
        customMappings: Map<String, CustomSourceMapping>,
        useMavenCentralMetadata: Boolean
    ): SourceMapping.Source? {
        val key = "${dependencyInfo.groupId}:${dependencyInfo.artifactId}"
        
        // 1. Check custom mappings first
        customMappings[key]?.let { customMapping ->
            logger.debug("Using custom mapping for $key")
            return SourceMapping.Source(
                local = customMapping.local,
                github = customMapping.github
            )
        }
        
        // 2. Try Maven Central metadata
        if (useMavenCentralMetadata) {
            metadataCache.get(key, dependencyInfo.version)?.let { cachedSource ->
                logger.debug("Using cached metadata for $key")
                return cachedSource
            }
            
            val pomFile = findPomFile(dependencyInfo)
            if (pomFile != null) {
                val scmInfo = parsePomForScm(pomFile)
                if (scmInfo != null) {
                    val githubSource = parseScmUrl(scmInfo, dependencyInfo.version)
                    if (githubSource != null) {
                        logger.debug("Resolved GitHub source for $key from POM")
                        
                        // Analyze the GitHub source to find the correct path
                        val analyzedSource = githubSourceAnalyzer.analyzeAndResolvePath(githubSource, dependencyInfo)
                        if (analyzedSource != null) {
                            val source = SourceMapping.Source(github = analyzedSource)
                            metadataCache.put(key, dependencyInfo.version, source)
                            return source
                        } else {
                            logger.debug("Failed to analyze GitHub source for $key, skipping")
                        }
                    }
                }
            }
        }
        
        // 3. Convention-based mapping for well-known artifacts
        val conventionSource = tryConventionBasedMapping(dependencyInfo)
        if (conventionSource != null) {
            logger.debug("Using convention-based mapping for $key")
            
            // Extract the GitHub source if present
            val githubSource = conventionSource.github
            if (githubSource != null) {
                // Analyze to find the correct path
                val analyzedSource = githubSourceAnalyzer.analyzeAndResolvePath(githubSource, dependencyInfo)
                if (analyzedSource != null) {
                    val source = SourceMapping.Source(github = analyzedSource)
                    metadataCache.put(key, dependencyInfo.version, source)
                    return source
                } else {
                    logger.debug("Failed to analyze convention-based GitHub source for $key, skipping")
                    return null
                }
            }
            
            metadataCache.put(key, dependencyInfo.version, conventionSource)
            return conventionSource
        }
        
        logger.warn("Could not resolve source location for $key")
        return null
    }
    
    /**
     * Find POM file in Gradle cache for the dependency
     */
    private fun findPomFile(dependencyInfo: DependencyInfo): File? {
        val gradleUserHome = project.gradle.gradleUserHomeDir
        val pomPath = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/" +
            "${dependencyInfo.groupId}/${dependencyInfo.artifactId}/${dependencyInfo.version}"
        )
        
        if (!pomPath.exists()) {
            return null
        }
        
        // Find POM file in the hash directories
        return pomPath.walkTopDown()
            .firstOrNull { it.name.endsWith(".pom") }
    }
    
    /**
     * Parse POM file to extract SCM information
     */
    private fun parsePomForScm(pomFile: File): ScmInfo? {
        try {
            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc: Document = docBuilder.parse(pomFile)
            doc.documentElement.normalize()
            
            // Try to get SCM from current POM
            val scmNodes = doc.getElementsByTagName("scm")
            if (scmNodes.length > 0) {
                val scmElement = scmNodes.item(0) as Element
                
                val url = getElementText(scmElement, "url")
                val connection = getElementText(scmElement, "connection")
                val developerConnection = getElementText(scmElement, "developerConnection")
                val tag = getElementText(scmElement, "tag")
                
                if (!url.isNullOrBlank() || !connection.isNullOrBlank() || !developerConnection.isNullOrBlank()) {
                    return ScmInfo(
                        url = url ?: connection ?: developerConnection ?: "",
                        connection = connection,
                        developerConnection = developerConnection,
                        tag = tag
                    )
                }
            }
            
            // If no SCM found, try parent POM
            val parentNodes = doc.getElementsByTagName("parent")
            if (parentNodes.length > 0) {
                val parentElement = parentNodes.item(0) as Element
                val parentGroupId = getElementText(parentElement, "groupId")
                val parentArtifactId = getElementText(parentElement, "artifactId")
                val parentVersion = getElementText(parentElement, "version")
                
                if (!parentGroupId.isNullOrBlank() && !parentArtifactId.isNullOrBlank() && !parentVersion.isNullOrBlank()) {
                    val parentPomFile = findPomFile(DependencyInfo(
                        groupId = parentGroupId,
                        artifactId = parentArtifactId,
                        version = parentVersion,
                        packages = emptySet()
                    ))
                    
                    if (parentPomFile != null) {
                        logger.debug("Checking parent POM: $parentGroupId:$parentArtifactId:$parentVersion")
                        return parsePomForScm(parentPomFile)
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            logger.debug("Failed to parse POM file ${pomFile.name}: ${e.message}")
            return null
        }
    }
    
    /**
     * Get text content of an XML element
     */
    private fun getElementText(parent: Element, tagName: String): String? {
        val nodes: NodeList = parent.getElementsByTagName(tagName)
        if (nodes.length == 0) {
            return null
        }
        return nodes.item(0).textContent?.trim()
    }
    
    /**
     * Parse SCM URL to extract GitHub information
     */
    private fun parseScmUrl(scmInfo: ScmInfo, version: String): GitHubSource? {
        val urls = listOfNotNull(
            scmInfo.url,
            scmInfo.connection,
            scmInfo.developerConnection
        )
        
        for (url in urls) {
            val githubSource = extractGitHubInfo(url, version, scmInfo.tag)
            if (githubSource != null) {
                return githubSource
            }
        }
        
        return null
    }
    
    /**
     * Extract GitHub owner and repo from various URL formats
     */
    private fun extractGitHubInfo(url: String, version: String, tag: String?): GitHubSource? {
        // Handle various GitHub URL formats:
        // https://github.com/owner/repo
        // git@github.com:owner/repo.git
        // scm:git:git://github.com/owner/repo.git
        // scm:git:https://github.com/owner/repo.git
        
        val patterns = listOf(
            Regex("""github\.com[:/]([^/]+)/([^/\s.]+)"""),
            Regex("""github\.com/([^/]+)/([^/\s.]+)\.git""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val owner = match.groupValues[1]
                val repo = match.groupValues[2].removeSuffix(".git")
                
                // Determine the ref (tag or version)
                val ref = when {
                    !tag.isNullOrBlank() && tag != "HEAD" -> tag
                    version.isNotBlank() -> normalizeVersionToTag(version)
                    else -> "main"
                }
                
                return GitHubSource(
                    owner = owner,
                    repo = repo,
                    ref = ref,
                    path = ""
                )
            }
        }
        
        return null
    }
    
    /**
     * Normalize version string to a likely Git tag format
     */
    private fun normalizeVersionToTag(version: String): String {
        // Common patterns: v1.2.3, 1.2.3, rel/1.2.3
        return when {
            version.startsWith("v") -> version
            version.matches(Regex("""\d+\.\d+.*""")) -> "v$version"
            else -> version
        }
    }
    
    /**
     * Try convention-based mapping for well-known libraries
     */
    private fun tryConventionBasedMapping(dependencyInfo: DependencyInfo): SourceMapping.Source? {
        val conventions = mapOf(
            // Spring Framework
            "org.springframework:spring-" to GitHubSource("spring-projects", "spring-framework", "v${dependencyInfo.version}"),
            "org.springframework.boot:spring-boot" to GitHubSource("spring-projects", "spring-boot", "v${dependencyInfo.version}"),
            
            // Apache Commons
            "org.apache.commons:commons-lang3" to GitHubSource("apache", "commons-lang", "rel/commons-lang-${dependencyInfo.version}"),
            "org.apache.commons:commons-collections4" to GitHubSource("apache", "commons-collections", "rel/commons-collections-${dependencyInfo.version}"),
            "commons-codec:commons-codec" to GitHubSource("apache", "commons-codec", "rel/commons-codec-${dependencyInfo.version}"),
            
            // Google libraries
            "com.google.guava:guava" to GitHubSource("google", "guava", "v${dependencyInfo.version}"),
            "com.google.protobuf:protobuf-" to GitHubSource("protocolbuffers", "protobuf", "v${dependencyInfo.version}"),
            "com.google.code.gson:gson" to GitHubSource("google", "gson", "gson-parent-${dependencyInfo.version}"),
            
            // Jackson
            "com.fasterxml.jackson.core:jackson-databind" to GitHubSource("FasterXML", "jackson-databind", "jackson-databind-${dependencyInfo.version}"),
            "com.fasterxml.jackson.core:jackson-core" to GitHubSource("FasterXML", "jackson-core", "jackson-core-${dependencyInfo.version}"),
            "com.fasterxml.jackson.dataformat:jackson-dataformat-" to GitHubSource("FasterXML", "jackson-dataformats-text", "jackson-dataformats-text-${dependencyInfo.version}"),
            
            // Logback
            "ch.qos.logback:logback-" to GitHubSource("qos-ch", "logback", "v_${dependencyInfo.version}"),
            
            // SLF4J
            "org.slf4j:slf4j-" to GitHubSource("qos-ch", "slf4j", "v_${dependencyInfo.version}"),
            
            // Apache Log4j
            "org.apache.logging.log4j:log4j-" to GitHubSource("apache", "logging-log4j2", "rel/${dependencyInfo.version}"),
            
            // Netty
            "io.netty:netty-" to GitHubSource("netty", "netty", "netty-${dependencyInfo.version}"),
            
            // gRPC
            "io.grpc:grpc-" to GitHubSource("grpc", "grpc-java", "v${dependencyInfo.version}")
        )
        
        val key = "${dependencyInfo.groupId}:${dependencyInfo.artifactId}"
        
        // Check exact match first
        conventions[key]?.let {
            return SourceMapping.Source(github = it)
        }
        
        // Check prefix match
        conventions.forEach { (prefix, githubSource) ->
            if (key.startsWith(prefix)) {
                return SourceMapping.Source(github = githubSource)
            }
        }
        
        return null
    }
}

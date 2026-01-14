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
    private val metadataCache: MetadataCache
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
                        val source = SourceMapping.Source(github = githubSource)
                        metadataCache.put(key, dependencyInfo.version, source)
                        return source
                    }
                }
            }
        }
        
        // 3. Convention-based mapping for well-known artifacts
        val conventionSource = tryConventionBasedMapping(dependencyInfo)
        if (conventionSource != null) {
            logger.debug("Using convention-based mapping for $key")
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
            
            val scmNodes = doc.getElementsByTagName("scm")
            if (scmNodes.length == 0) {
                return null
            }
            
            val scmElement = scmNodes.item(0) as Element
            
            val url = getElementText(scmElement, "url")
            val connection = getElementText(scmElement, "connection")
            val developerConnection = getElementText(scmElement, "developerConnection")
            val tag = getElementText(scmElement, "tag")
            
            if (url.isNullOrBlank() && connection.isNullOrBlank() && developerConnection.isNullOrBlank()) {
                return null
            }
            
            return ScmInfo(
                url = url ?: connection ?: developerConnection ?: "",
                connection = connection,
                developerConnection = developerConnection,
                tag = tag
            )
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
            
            // Google Guava
            "com.google.guava:guava" to GitHubSource("google", "guava", "v${dependencyInfo.version}"),
            
            // Jackson
            "com.fasterxml.jackson.core:jackson-databind" to GitHubSource("FasterXML", "jackson-databind", "jackson-databind-${dependencyInfo.version}"),
            "com.fasterxml.jackson.core:jackson-core" to GitHubSource("FasterXML", "jackson-core", "jackson-core-${dependencyInfo.version}"),
            
            // Logback
            "ch.qos.logback:logback-" to GitHubSource("qos-ch", "logback", "v_${dependencyInfo.version}"),
            
            // SLF4J
            "org.slf4j:slf4j-" to GitHubSource("qos-ch", "slf4j", "v_${dependencyInfo.version}")
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

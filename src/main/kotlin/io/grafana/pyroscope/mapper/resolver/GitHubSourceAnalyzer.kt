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

import io.grafana.pyroscope.mapper.model.DependencyInfo
import io.grafana.pyroscope.mapper.model.GitHubSource
import org.gradle.api.logging.Logger
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Analyzes GitHub repositories to find the correct source path for dependencies
 */
class GitHubSourceAnalyzer(
    private val logger: Logger,
    private val cacheDir: File
) {
    
    /**
     * Analyze a GitHub source to find the correct path for a dependency
     * Returns an updated GitHubSource with the path set, or null if analysis fails
     */
    fun analyzeAndResolvePath(
        githubSource: GitHubSource,
        dependencyInfo: DependencyInfo
    ): GitHubSource? {
        try {
            logger.debug("Analyzing GitHub source: ${githubSource.owner}/${githubSource.repo}@${githubSource.ref}")
            
            // Download the tar.gz archive
            val archiveFile = downloadArchive(githubSource)
            if (archiveFile == null) {
                logger.debug("Failed to download archive for ${githubSource.owner}/${githubSource.repo}")
                return null
            }
            
            // Find the path where the classes from this dependency are located
            val path = findClassPath(archiveFile, dependencyInfo)
            if (path == null) {
                logger.debug("Could not find matching path for ${dependencyInfo.groupId}:${dependencyInfo.artifactId} in ${githubSource.owner}/${githubSource.repo}")
                return null
            }
            
            logger.debug("Found path '$path' for ${dependencyInfo.groupId}:${dependencyInfo.artifactId}")
            
            // Clean up the archive file
            archiveFile.delete()
            
            return githubSource.copy(path = path)
        } catch (e: Exception) {
            logger.debug("Error analyzing GitHub source ${githubSource.owner}/${githubSource.repo}: ${e.message}")
            return null
        }
    }
    
    /**
     * Download the tar.gz archive from GitHub
     */
    private fun downloadArchive(githubSource: GitHubSource): File? {
        val url = "https://github.com/${githubSource.owner}/${githubSource.repo}/archive/refs/tags/${githubSource.ref}.tar.gz"
        
        try {
            logger.debug("Downloading from: $url")
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                logger.debug("HTTP $responseCode when downloading $url")
                return null
            }
            
            // Save to cache directory
            val cacheFile = File(cacheDir, "${githubSource.owner}-${githubSource.repo}-${githubSource.ref}.tar.gz")
            cacheDir.mkdirs()
            
            connection.inputStream.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            logger.debug("Downloaded archive to ${cacheFile.absolutePath}")
            return cacheFile
        } catch (e: Exception) {
            logger.debug("Failed to download archive: ${e.message}")
            return null
        }
    }
    
    /**
     * Find the path in the archive where the classes from this dependency are located
     */
    private fun findClassPath(archiveFile: File, dependencyInfo: DependencyInfo): String? {
        try {
            // Extract and analyze the archive
            val javaFiles = mutableMapOf<String, MutableList<String>>() // path -> list of packages found
            
            TarArchiveInputStream(GZIPInputStream(BufferedInputStream(archiveFile.inputStream()))).use { tarInput ->
                var entry = tarInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".java")) {
                        // Skip module-info and META-INF files
                        if (entry.name.endsWith("module-info.java") || entry.name.contains("/META-INF/")) {
                            entry = tarInput.nextEntry
                            continue
                        }
                        
                        // Read the package declaration
                        val content = tarInput.readBytes().toString(Charsets.UTF_8)
                        val packageName = extractPackageFromJavaFile(content)
                        
                        if (packageName != null) {
                            // The entry name typically looks like: repo-name-tag/path/to/file.java
                            // We need to extract the path part
                            val parts = entry.name.split('/', limit = 2)
                            if (parts.size == 2) {
                                val relativePath = parts[1]
                                val directoryPath = relativePath.substringBeforeLast('/')
                                
                                // Find where the package path starts in the directory path
                                val packagePath = packageName.replace('.', '/')
                                val index = directoryPath.indexOf(packagePath)
                                
                                if (index >= 0) {
                                    val sourcePath = if (index > 0) {
                                        directoryPath.substring(0, index).trimEnd('/')
                                    } else {
                                        ""
                                    }
                                    
                                    javaFiles.getOrPut(sourcePath) { mutableListOf() }.add(packagePath)
                                }
                            }
                        }
                    }
                    entry = tarInput.nextEntry
                }
            }
            
            // Find the path that contains the most packages from our dependency
            return findBestMatchingPath(javaFiles, dependencyInfo.packages)
        } catch (e: Exception) {
            logger.debug("Error analyzing archive: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract package declaration from Java file content
     */
    private fun extractPackageFromJavaFile(content: String): String? {
        val packageRegex = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
        val match = packageRegex.find(content)
        return match?.groupValues?.get(1)
    }
    
    /**
     * Find the path that best matches the packages from the dependency
     */
    private fun findBestMatchingPath(
        pathsWithPackages: Map<String, List<String>>,
        targetPackages: Set<String>
    ): String? {
        if (pathsWithPackages.isEmpty()) {
            return null
        }
        
        // Score each path based on how many target packages it contains
        val scores = pathsWithPackages.mapValues { (_, packages) ->
            packages.count { pkg -> targetPackages.contains(pkg) }
        }
        
        // Find the path with the highest score
        val bestPath = scores.maxByOrNull { it.value }
        
        // Only return a path if it contains at least some of the target packages
        return if (bestPath != null && bestPath.value > 0) {
            bestPath.key
        } else {
            null
        }
    }
}

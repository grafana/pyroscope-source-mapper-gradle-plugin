package io.grafana.pyroscope.mapper.resolver

import io.grafana.pyroscope.mapper.model.SourceMapping
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Cache for Maven metadata lookups to avoid repeated POM parsing
 */
class MetadataCache(private val cacheFile: File) {
    
    private val cache: MutableMap<String, CacheEntry> = mutableMapOf()
    
    init {
        loadCache()
    }
    
    /**
     * Get cached source mapping for a dependency
     */
    fun get(key: String, version: String): SourceMapping.Source? {
        val cacheKey = "$key:$version"
        return cache[cacheKey]?.source
    }
    
    /**
     * Put source mapping into cache
     */
    fun put(key: String, version: String, source: SourceMapping.Source) {
        val cacheKey = "$key:$version"
        cache[cacheKey] = CacheEntry(source, System.currentTimeMillis())
    }
    
    /**
     * Save cache to disk
     */
    fun save() {
        try {
            cacheFile.parentFile?.mkdirs()
            ObjectOutputStream(cacheFile.outputStream()).use { oos ->
                oos.writeObject(HashMap(cache))
            }
        } catch (e: Exception) {
            // Log but don't fail if cache cannot be saved
            println("Warning: Failed to save metadata cache: ${e.message}")
        }
    }
    
    /**
     * Load cache from disk
     */
    private fun loadCache() {
        if (!cacheFile.exists()) {
            return
        }
        
        try {
            ObjectInputStream(cacheFile.inputStream()).use { ois ->
                @Suppress("UNCHECKED_CAST")
                val loaded = ois.readObject() as Map<String, CacheEntry>
                cache.putAll(loaded)
                
                // Clean up old entries (older than 30 days)
                val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                cache.entries.removeIf { it.value.timestamp < cutoffTime }
            }
        } catch (e: Exception) {
            // If cache is corrupted or incompatible, start fresh
            println("Warning: Failed to load metadata cache, starting fresh: ${e.message}")
            cache.clear()
        }
    }
    
    /**
     * Cache entry with timestamp
     */
    private data class CacheEntry(
        val source: SourceMapping.Source,
        val timestamp: Long
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

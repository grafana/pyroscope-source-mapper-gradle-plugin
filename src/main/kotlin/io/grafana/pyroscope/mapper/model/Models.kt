package io.grafana.pyroscope.mapper.model

/**
 * Root configuration for .pyroscope.yaml file
 */
data class PyroscopeConfig(
    val version: String,
    val sourceCode: SourceCode
) {
    data class SourceCode(
        val mappings: List<SourceMapping>
    )
}

/**
 * Individual source mapping entry
 */
data class SourceMapping(
    val functionName: List<FunctionPrefix>,
    val language: String,
    val source: Source
) {
    data class FunctionPrefix(
        val prefix: List<String>
    )
    
    data class Source(
        val local: LocalSource? = null,
        val github: GitHubSource? = null
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * Local source location
 */
data class LocalSource(
    val path: String
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * GitHub source location
 */
data class GitHubSource(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String = ""
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Custom mapping provided by user
 */
data class CustomSourceMapping(
    val github: GitHubSource? = null,
    val local: LocalSource? = null
) {
    init {
        require(github != null || local != null) {
            "Either github or local source must be specified"
        }
    }
}

/**
 * Resolved dependency information
 */
data class DependencyInfo(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packages: Set<String>
)

/**
 * SCM information extracted from POM
 */
data class ScmInfo(
    val url: String,
    val connection: String? = null,
    val developerConnection: String? = null,
    val tag: String? = null
)

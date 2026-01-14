package io.grafana.pyroscope.mapper

import io.grafana.pyroscope.mapper.model.CustomSourceMapping

open class PyroscopeSourceMapperExtension {
    var outputFile: String = ".pyroscope.yaml"
    var includeConfigs: List<String> = listOf("runtimeClasspath")
    var skipConfigs: List<String> = listOf()
    var includeLocalProject: Boolean = true
    var language: String = "java"
    var version: String = "v1"
    var useMavenCentralMetadata: Boolean = true
    var customMappings: Map<String, CustomSourceMapping> = mapOf()
}

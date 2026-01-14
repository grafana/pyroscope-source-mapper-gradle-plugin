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
package io.grafana.pyroscope.mapper.generator

import io.grafana.pyroscope.mapper.model.PyroscopeConfig
import io.grafana.pyroscope.mapper.model.SourceMapping
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.StringWriter

class YamlGenerator {
    
    /**
     * Generate .pyroscope.yaml file from configuration
     */
    fun generate(config: PyroscopeConfig, outputFile: File) {
        val yaml = createYaml()
        val yamlString = yaml.dump(convertToMap(config))
        
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(yamlString)
    }
    
    /**
     * Create YAML serializer with proper formatting
     */
    private fun createYaml(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        }
        return Yaml(options)
    }
    
    /**
     * Convert PyroscopeConfig to a Map structure for YAML serialization
     */
    private fun convertToMap(config: PyroscopeConfig): Map<String, Any> {
        return mapOf(
            "version" to config.version,
            "source_code" to mapOf(
                "mappings" to config.sourceCode.mappings.map { convertMapping(it) }
            )
        )
    }
    
    /**
     * Convert SourceMapping to Map
     */
    private fun convertMapping(mapping: SourceMapping): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        // function_name field - each prefix is a separate map entry
        result["function_name"] = mapping.functionName.map { 
            mapOf("prefix" to it.prefix)
        }
        
        // language field
        result["language"] = mapping.language
        
        // source field
        val sourceMap = mutableMapOf<String, Any>()
        
        mapping.source.local?.let { local ->
            sourceMap["local"] = mapOf("path" to local.path)
        }
        
        mapping.source.github?.let { github ->
            val githubMap = mutableMapOf<String, Any>(
                "owner" to github.owner,
                "repo" to github.repo,
                "ref" to github.ref
            )
            if (github.path.isNotEmpty()) {
                githubMap["path"] = github.path
            }
            sourceMap["github"] = githubMap
        }
        
        result["source"] = sourceMap
        
        return result
    }
}

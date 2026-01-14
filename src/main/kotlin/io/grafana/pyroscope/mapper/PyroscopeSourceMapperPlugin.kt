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

import org.gradle.api.Plugin
import org.gradle.api.Project

class PyroscopeSourceMapperPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "pyroscopeSourceMapper",
            PyroscopeSourceMapperExtension::class.java
        )
        
        project.tasks.register("generatePyroscopeSourceMap", PyroscopeSourceMapperTask::class.java).configure {
            group = "pyroscope"
            description = "Generates .pyroscope.yaml file mapping dependencies to source repositories"
            
            outputFile.set(project.layout.projectDirectory.file(extension.outputFile))
            includeConfigs.set(extension.includeConfigs)
            skipConfigs.set(extension.skipConfigs)
            includeLocalProject.set(extension.includeLocalProject)
            includeJavaStdlib.set(extension.includeJavaStdlib)
            language.set(extension.language)
            version.set(extension.version)
            useMavenCentralMetadata.set(extension.useMavenCentralMetadata)
            customMappings.set(extension.customMappings)
            cacheDir.set(project.layout.buildDirectory.dir("pyroscope-mapper-cache"))
        }
    }
}

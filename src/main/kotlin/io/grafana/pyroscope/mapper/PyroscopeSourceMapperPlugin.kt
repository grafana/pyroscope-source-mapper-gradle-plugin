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
            language.set(extension.language)
            version.set(extension.version)
            useMavenCentralMetadata.set(extension.useMavenCentralMetadata)
            customMappings.set(extension.customMappings)
            cacheDir.set(project.layout.buildDirectory.dir("pyroscope-mapper-cache"))
        }
    }
}

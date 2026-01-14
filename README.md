# Pyroscope Source Mapper Gradle Plugin

A Gradle plugin that automatically generates `.pyroscope.yaml` files mapping Java dependencies to their source code repositories. This enables Pyroscope to provide source-level profiling information for your entire dependency tree.

## Features

- 🔍 **Automatic dependency traversal** - Analyzes all project dependencies (direct and transitive)
- 📦 **Package extraction** - Scans JAR files to extract package prefixes
- 🔗 **Smart source resolution** - Maps dependencies to GitHub repositories using:
  - Maven POM SCM metadata
  - Convention-based mappings for popular libraries
  - Custom user-defined mappings
- 💾 **Metadata caching** - Caches Maven metadata lookups for faster subsequent runs
- 🎯 **Local project support** - Optionally includes local project source mappings
- ⚡ **Incremental builds** - Supports Gradle's build cache and up-to-date checking

## Installation

Add the plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("io.grafana.pyroscope.source-mapper") version "0.1.0"
}
```

## Usage

### Basic Configuration

```kotlin
pyroscopeSourceMapper {
    outputFile = ".pyroscope.yaml"
    includeConfigs = listOf("runtimeClasspath")
    includeLocalProject = true
}
```

### Advanced Configuration

```kotlin
pyroscopeSourceMapper {
    outputFile = ".pyroscope.yaml"
    
    // Configurations to analyze
    includeConfigs = listOf("runtimeClasspath", "compileClasspath")
    
    // Configurations to skip (regex patterns supported)
    skipConfigs = listOf("testRuntimeClasspath", ".*test.*")
    
    // Include local project source code
    includeLocalProject = true
    
    // Language (currently only "java" supported)
    language = "java"
    
    // Pyroscope config version
    version = "v1"
    
    // Use Maven Central POM files for SCM metadata
    useMavenCentralMetadata = true
    
    // Custom mappings for dependencies not in Maven Central
    customMappings = mapOf(
        "com.example:custom-lib" to CustomSourceMapping(
            github = GitHubSource(
                owner = "example-org",
                repo = "custom-lib",
                ref = "v1.0.0",
                path = ""
            )
        ),
        "com.internal:internal-lib" to CustomSourceMapping(
            local = LocalSource(
                path = "/path/to/internal-lib/src"
            )
        )
    )
}
```

### Running the Task

Generate the `.pyroscope.yaml` file:

```bash
./gradlew generatePyroscopeSourceMap
```

Integrate with your build:

```kotlin
tasks.register("buildWithSourceMap") {
    dependsOn("generatePyroscopeSourceMap", "build")
}
```

## How It Works

1. **Dependency Resolution**: The plugin resolves all dependencies from specified Gradle configurations (e.g., `runtimeClasspath`)

2. **Package Extraction**: For each dependency JAR file, it scans for `.class` files and extracts unique package prefixes

3. **Filtering**: Dependencies without packages are automatically skipped. This includes:
   - BOM (Bill of Materials) artifacts (e.g., `jackson-bom`, `spring-boot-dependencies`)
   - Starter/aggregator POMs (e.g., `spring-boot-starter-*`)
   - Empty placeholder artifacts (e.g., `guava:listenablefuture`)

4. **Source Location Resolution**: For each dependency with packages, it attempts to find the source repository in this order:
   - User-provided custom mappings
   - Maven Central POM files (SCM section)
   - Convention-based mappings for well-known libraries
   - Falls back to warning if source cannot be determined

5. **YAML Generation**: Creates a `.pyroscope.yaml` file with mappings from package paths to source repositories

6. **Caching**: Stores resolved metadata in `build/pyroscope-mapper-cache/` for faster subsequent runs

## Output Format

The generated `.pyroscope.yaml` follows this structure:

```yaml
version: v1
source_code:
  mappings:
    - function_name:
        - prefix: [com/fasterxml/jackson]
      language: java
      source:
        github:
          owner: FasterXML
          repo: jackson-databind
          ref: jackson-databind-2.15.3
          path: ""
    - function_name:
        - prefix: [com/example/app]
      language: java
      source:
        local:
          path: src/main/java
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `outputFile` | String | `.pyroscope.yaml` | Output file path |
| `includeConfigs` | List<String> | `["runtimeClasspath"]` | Gradle configurations to analyze |
| `skipConfigs` | List<String> | `[]` | Configurations to skip (supports regex) |
| `includeLocalProject` | Boolean | `true` | Include local project source mapping |
| `language` | String | `"java"` | Programming language |
| `version` | String | `"v1"` | Pyroscope config format version |
| `useMavenCentralMetadata` | Boolean | `true` | Parse Maven POM files for SCM info |
| `customMappings` | Map | `{}` | Custom source mappings |

## Supported Libraries

The plugin has built-in convention mappings for popular libraries:

- Spring Framework & Spring Boot
- Google Guava
- Jackson (databind, core)
- Apache Commons
- Logback & SLF4J
- And many more...

For other libraries, it will attempt to parse the Maven POM file's SCM section.

## Troubleshooting

### Dependencies not resolved

Enable debug logging to see detailed resolution information:

```bash
./gradlew generatePyroscopeSourceMap --info
```

### Custom mappings not working

Ensure the dependency key format is correct: `"groupId:artifactId"`

### Source locations incorrect

You can override any resolved source location using custom mappings.

## Development

### Building the Plugin

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

### Testing Locally

Use the example project:

```bash
cd example
./gradlew generatePyroscopeSourceMap
cat .pyroscope.yaml
```

### Using in Another Project Locally

To use this plugin in your own project without publishing, use a composite build.

In your project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("/path/to/pyroscope-source-mapper-gradle-plugin")
}
```

Then use the plugin normally:

```kotlin
plugins {
    id("io.grafana.pyroscope.source-mapper")
}
```

See [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) for more options and detailed instructions.

## License

[Add your license here]

## Contributing

Contributions are welcome! Please open an issue or pull request.

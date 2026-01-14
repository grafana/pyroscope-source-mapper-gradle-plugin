# Pyroscope Source Mapper Gradle Plugin

> ⚠️ **Experimental**: This project is an experiment and is not officially supported. Use at your own risk.

A Gradle plugin that automatically generates `.pyroscope.yaml` files mapping Java dependencies to their source code repositories. This enables Pyroscope to provide source-level profiling information for your entire dependency tree.

## Features

- 🔍 **Automatic dependency traversal** - Analyzes all project dependencies (direct and transitive)
- 📦 **Package extraction** - Scans JAR files to extract package prefixes
- 🔗 **Smart source resolution** - Maps dependencies to GitHub repositories using:
  - Maven POM SCM metadata
  - Convention-based mappings for popular libraries
  - Custom user-defined mappings
- ☕ **Java stdlib support** - Automatically maps Java standard library packages to OpenJDK source
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
    includeJavaStdlib = true  // Map Java stdlib to OpenJDK source
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

    // Include Java standard library mappings to OpenJDK source
    // Automatically detects Java version and maps to appropriate OpenJDK tag
    includeJavaStdlib = true

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
    # Local project source
    - function_name:
        - prefix: com/example/app
      language: java
      source:
        local:
          path: src/main/java

    # Java standard library (automatically mapped to OpenJDK)
    - function_name:
        - prefix: java/lang
        - prefix: java/util
        - prefix: java/io
        # ... (28 more Java stdlib packages)
      language: java
      source:
        github:
          owner: openjdk
          repo: jdk
          ref: jdk-21+0  # Automatically matched to your Java version
          path: src/java.base/share/classes

    # Third-party dependencies
    - function_name:
        - prefix: com/fasterxml/jackson/databind
        - prefix: com/fasterxml/jackson/databind/annotation
        - prefix: com/fasterxml/jackson/databind/cfg
      language: java
      source:
        github:
          owner: FasterXML
          repo: jackson-databind
          ref: jackson-databind-2.15.3
          path: src/main/java
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `outputFile` | String | `.pyroscope.yaml` | Output file path |
| `includeConfigs` | List<String> | `["runtimeClasspath"]` | Gradle configurations to analyze |
| `skipConfigs` | List<String> | `[]` | Configurations to skip (supports regex) |
| `includeLocalProject` | Boolean | `true` | Include local project source mapping |
| `includeJavaStdlib` | Boolean | `true` | Include Java stdlib mappings to OpenJDK source |
| `language` | String | `"java"` | Programming language |
| `version` | String | `"v1"` | Pyroscope config format version |
| `useMavenCentralMetadata` | Boolean | `true` | Parse Maven POM files for SCM info |
| `customMappings` | Map | `{}` | Custom source mappings |

## Supported Libraries

The plugin has built-in convention mappings for popular libraries:

- **Java Standard Library** - Automatically mapped to OpenJDK source (version-matched)
- Spring Framework & Spring Boot
- Google Guava
- Jackson (databind, core)
- Apache Commons
- Logback & SLF4J
- And many more...

For other libraries, it will attempt to parse the Maven POM file's SCM section.

### Java Standard Library Mapping

When `includeJavaStdlib` is enabled (default), the plugin automatically maps common Java packages to the OpenJDK repository. The Java version is detected in the following order:

1. Java toolchain configuration (if configured in your build)
2. Target compatibility setting
3. Runtime Java version (fallback)

The detected version is mapped to the corresponding OpenJDK tag (e.g., Java 21 → `jdk-21+0`). This includes packages from:

- **java.base**: `java.lang`, `java.util`, `java.io`, `java.nio`, `java.net`, `java.time`, etc.
- **java.sql**: `java.sql`, `javax.sql`
- **java.desktop**: `java.awt`, `javax.swing`, `javax.imageio`, etc.
- **Other modules**: `java.rmi`, `javax.naming`, `javax.management`, `javax.xml`, etc.
- **Internal APIs**: `jdk.internal.*`, `sun.*`, `com.sun.*`

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

Apache License 2.0 - See [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please open an issue or pull request.

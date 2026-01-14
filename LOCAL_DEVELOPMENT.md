# Using the Plugin Locally

There are several ways to test this Gradle plugin locally without publishing it to a repository.

## Option 1: Composite Build (Recommended)

This is the approach already used in the `example/` directory. It's the cleanest way to test locally.

### In your test project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("/path/to/pyroscope-source-mapper-gradle-plugin")
}

rootProject.name = "my-test-project"
```

### In your test project's `build.gradle.kts`:

```kotlin
plugins {
    java
    id("io.grafana.pyroscope.source-mapper")
}

// ... rest of your configuration
```

The plugin will be automatically built and included when you run Gradle commands.

## Option 2: Maven Local Repository

Publish the plugin to your local Maven repository (`~/.m2/repository`).

### Step 1: Publish to Maven Local

In the plugin directory:

```bash
cd /path/to/pyroscope-source-mapper-gradle-plugin
./gradlew publishToMavenLocal
```

### Step 2: Use in your test project

In your test project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

In your test project's `build.gradle.kts`:

```kotlin
plugins {
    java
    id("io.grafana.pyroscope.source-mapper") version "0.1.0-SNAPSHOT"
}
```

## Option 3: Gradle Plugin Portal Development

For testing the plugin as if it were published:

### Step 1: Build the plugin

```bash
cd /path/to/pyroscope-source-mapper-gradle-plugin
./gradlew build
```

### Step 2: Use `includeBuild` in settings

In your test project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("/path/to/pyroscope-source-mapper-gradle-plugin")
}
```

## Option 4: Copy the Plugin JAR

Build and manually add the plugin JAR to your test project.

### Step 1: Build the plugin

```bash
cd /path/to/pyroscope-source-mapper-gradle-plugin
./gradlew build
```

The JAR will be at: `build/libs/pyroscope-source-mapper-gradle-plugin-0.1.0-SNAPSHOT.jar`

### Step 2: Create a local plugin repository

In your test project:

```
my-test-project/
├── build.gradle.kts
├── settings.gradle.kts
└── local-plugins/
    └── pyroscope-source-mapper-gradle-plugin-0.1.0-SNAPSHOT.jar
```

### Step 3: Configure settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        flatDir {
            dirs("local-plugins")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Quick Test: Use the Example Project

The easiest way to test is to use the existing example:

```bash
cd /path/to/pyroscope-source-mapper-gradle-plugin/example
./gradlew generatePyroscopeSourceMap
cat .pyroscope.yaml
```

Or modify the example to match your project's dependencies:

```bash
cd /path/to/pyroscope-source-mapper-gradle-plugin/example
# Edit build.gradle.kts to add your dependencies
vim build.gradle.kts
./gradlew generatePyroscopeSourceMap
```

## Recommended Workflow for Development

1. **Make changes** to the plugin code
2. **No need to rebuild** - composite builds auto-rebuild
3. **Run in test project**:
   ```bash
   cd /path/to/your-test-project
   ./gradlew generatePyroscopeSourceMap
   ```
4. **Iterate** - changes to plugin code are picked up automatically

## Troubleshooting

### Plugin not found
If you get "Plugin with id 'io.grafana.pyroscope.source-mapper' not found":

- Check that `includeBuild()` path is correct and absolute
- Try running `./gradlew tasks` in the plugin directory to ensure it builds
- Use `--info` flag: `./gradlew generatePyroscopeSourceMap --info`

### Changes not picked up
If your plugin changes aren't reflected:

```bash
# Clean both projects
cd /path/to/pyroscope-source-mapper-gradle-plugin
./gradlew clean

cd /path/to/your-test-project
./gradlew clean
./gradlew generatePyroscopeSourceMap
```

### Version conflicts
If you have issues with versions:

- Remove the version number when using `includeBuild` (Gradle handles it)
- Clear Gradle caches: `rm -rf ~/.gradle/caches/`

## Example Test Project Setup

Here's a complete minimal example:

### Directory structure:
```
workspace/
├── pyroscope-source-mapper-gradle-plugin/  (the plugin)
└── my-app/                                  (your test project)
    ├── settings.gradle.kts
    ├── build.gradle.kts
    └── src/
```

### my-app/settings.gradle.kts:
```kotlin
pluginManagement {
    includeBuild("../pyroscope-source-mapper-gradle-plugin")
}

rootProject.name = "my-app"
```

### my-app/build.gradle.kts:
```kotlin
plugins {
    java
    id("io.grafana.pyroscope.source-mapper")
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")
}

pyroscopeSourceMapper {
    outputFile = ".pyroscope.yaml"
    includeConfigs = listOf("runtimeClasspath")
    includeLocalProject = true
}
```

### Test it:
```bash
cd my-app
./gradlew generatePyroscopeSourceMap
cat .pyroscope.yaml
```

That's it! The composite build approach is the cleanest and most efficient for local development.

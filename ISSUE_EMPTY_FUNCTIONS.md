# Issue Analysis: Empty Function Names in Generated YAML

## Problem

The generated `.pyroscope.yaml` file contained entries with empty `function_name` arrays:

```yaml
- function_name: []
  language: java
  source:
    github:
      owner: spring-projects
      repo: spring-boot
      ref: v3.2.0
```

This is invalid because Pyroscope needs package prefixes to map profiling data to source code.

## Root Cause

The plugin was processing **all** resolved dependencies, including:

1. **BOM (Bill of Materials) Artifacts**
   - Example: `com.fasterxml.jackson:jackson-bom`
   - These are POM-only artifacts that manage dependency versions
   - They contain no JAR files or .class files

2. **Starter/Aggregator POMs**
   - Example: `org.springframework.boot:spring-boot-starter-*`
   - These are convenience dependencies that pull in multiple actual libraries
   - They are POM-only with no code

3. **Empty Placeholder Artifacts**
   - Example: `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava`
   - Special artifacts used to resolve dependency conflicts
   - Intentionally empty

## Solution

Added a filter to skip dependencies with no packages before creating source mappings:

```kotlin
dependencies.forEach { dependency ->
    // Skip dependencies with no packages
    // This includes:
    // - BOM (Bill of Materials) dependencies (e.g., jackson-bom, spring-boot-dependencies)
    // - Starter/aggregator POMs (e.g., spring-boot-starter-*)
    // - Empty placeholder artifacts (e.g., guava listenablefuture)
    if (dependency.packages.isEmpty()) {
        logger.debug("Skipping ${dependency.groupId}:${dependency.artifactId} - no packages found (likely BOM/POM-only artifact)")
        return@forEach
    }
    
    // ... continue with source resolution
}
```

## Results

**Before:**
- 41 dependencies analyzed
- 28 mappings generated (including 6 with empty function_name arrays)
- 1,437 lines of YAML

**After:**
- 41 dependencies analyzed
- 22 mappings generated (all valid)
- 1,389 lines of YAML
- 0 empty function_name arrays

## Examples of Skipped Dependencies

Based on the example Spring Boot project:

- `org.springframework.boot:spring-boot-starter` (POM aggregator)
- `org.springframework.boot:spring-boot-dependencies` (BOM)
- `com.fasterxml.jackson:jackson-bom` (BOM)
- `com.google.guava:listenablefuture` (empty placeholder)
- Other starter POMs without actual code

## Why This is Correct

These artifacts serve organizational/dependency management purposes but contain no actual code that would appear in profiling data. Skipping them:

1. **Prevents invalid YAML entries** that Pyroscope can't use
2. **Reduces noise** in the configuration file
3. **Improves clarity** - only code-containing dependencies are mapped
4. **Matches Pyroscope's requirements** - mappings must have package prefixes

## Verification

```bash
# No empty function_name arrays
cat .pyroscope.yaml | grep "function_name: \[\]"
# (returns nothing)

# All mappings have at least one prefix
cat .pyroscope.yaml | grep -A 2 "function_name:" | grep "prefix:"
# (shows all valid prefixes)
```

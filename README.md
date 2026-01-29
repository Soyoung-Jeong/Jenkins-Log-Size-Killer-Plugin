# Jenkins Log Size Killer Plugin

This plugin prevents Jenkins builds from consuming too much disk space or memory by monitoring:
1.  **Build Log Size**: Aborts the build if the console log exceeds a configured limit.
2.  **Workspace Size**: Periodically checks the workspace size causing the build to be aborted if it exceeds a limit.

## Features
- **Global Protection**: Set default limits for all jobs (Freestyle & Pipeline) to prevent disk exhaustion.
- **Pipeline Support**: Use the `logSizeLimit` step for precise, scoped control in Pipelines.
- **Dual Monitoring**:
    - **Log Size**: Checks build log file size periodically (Global) or real-time stream (Pipeline Step).
    - **Workspace Size**: Periodically checks workspace usage on agents.

## Configuration (Global)
Go to `Manage Jenkins` -> `System` -> `Log Size Killer`.

- **Enable**: specific if the global protection is active.
- **Max Log Size (MB)**: Global limit for build logs. If a build log exceeds this, it is aborted.
- **Max Workspace Size (MB)**: Global limit for workspace. Checked periodically.
- **Check Interval (seconds)**: How often to check sizes (minimum 10s).

> **Note**: Global settings apply to **ALL** jobs. If you want specific limits for specific jobs, set the global Log Limit to `0` (disabled) or a high safety net value, and use the Pipeline step below.

## Usage in Pipeline (DSL)
You can enforce strict log limits on specific blocks of code using the `logSizeLimit` step. This is useful for wrapping commands known to be verbose (like builds or tests).

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    // Abort if THIS block generates more than 5MB of log
                    logSizeLimit(size: 5) {
                        sh './build-massive-project.sh'
                    }
                }
            }
        }
    }
}
```

## How they work together?
If both Global Configuration and Pipeline Step are active, **the stricter limit applies**.

1. **Global Limit**: Checks the *total* size of the entire build log file.
    - Example: Global limit 10MB.
    - If the log reaches 10MB anywhere, the build dies.
2. **Pipeline Step**: Checks the size of logs generated *inside the block*.
    - Example: `logSizeLimit(size: 5) { ... }`
    - If the commands inside the block write more than 5MB, the build dies.

**Conflict Scenario**:
- If Global = 10MB and Step = 20MB: The build will likely die around 10MB (enforced by Global monitor).
- If Global = 50MB and Step = 5MB: The build will die if the step generates > 5MB.

## Installation
1. Build the plugin: `mvn clean package`
2. Upload `target/log-size-killer.hpi` to Jenkins via `Manage Jenkins` -> `Plugins` -> `Advanced settings` -> `Deploy Plugin`.

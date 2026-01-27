# Jenkins Log Size Killer Plugin

This plugin prevents Jenkins builds from consuming too much disk space or memory by monitoring:
1.  **Build Log Size**: Aborts the build if the console log exceeds a configured limit.
2.  **Workspace Size**: Periodically checks the workspace size causing the build to be aborted if it exceeds a limit.

## Features
- **Global Configuration**: Set limits globally for all jobs.
- **Log Monitoring**: Real-time monitoring of console output output stream.
- **Workspace Monitoring**: Periodic background check of workspace usage (currently supports Freestyle projects).

## Configuration
Go to `Manage Jenkins` -> `System` -> `Log Size Killer`.

- **Enable**: check to enable the plugin.
- **Max Log Size (bytes)**: The maximum allowed size for a build log. Default: 10MB.
- **Max Workspace Size (bytes)**: The maximum allowed size for the workspace content. Set to 0 to disable.
- **Check Interval (seconds)**: Frequency of workspace size checks. Default: 60s.

## Installation
1. Build the plugin: `mvn clean package`
2. Upload `target/log-size-killer.hpi` to Jenkins via `Manage Jenkins` -> `Plugins` -> `Advanced settings` -> `Deploy Plugin`.

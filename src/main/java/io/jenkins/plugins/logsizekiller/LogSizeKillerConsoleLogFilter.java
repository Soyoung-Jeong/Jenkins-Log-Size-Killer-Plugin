package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

@Extension(optional = true)
public class LogSizeKillerConsoleLogFilter extends ConsoleLogFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public OutputStream decorateLogger(Run build, OutputStream logger) throws IOException, InterruptedException {
        // We now rely on ResourceMonitorRunListener for both Log and Workspace size monitoring.
        // This ensures consistent behavior across Freestyle and Pipeline jobs.
        return logger;
    }
}

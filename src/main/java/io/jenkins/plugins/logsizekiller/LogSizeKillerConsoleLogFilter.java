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
    private final Long limitBytes;

    public LogSizeKillerConsoleLogFilter() {
        this.limitBytes = null;
    }

    public LogSizeKillerConsoleLogFilter(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    @Override
    public OutputStream decorateLogger(Run build, OutputStream logger) throws IOException, InterruptedException {
        long limit = 0;
        
        if (limitBytes != null) {
            limit = limitBytes;
        } else {
            // Fallback to global config if no explicit limit provided (Legacy mode)
            LogSizeKillerGlobalConfiguration config = LogSizeKillerGlobalConfiguration.get();
            if (config != null && config.isEnabled()) {
                limit = config.getMaxLogSizeBytes();
            }
        }

        if (limit <= 0) {
            return logger;
        }

        return new SizeCountingOutputStream(logger, build, limit);
    }

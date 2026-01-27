package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.Executor;
import hudson.console.LineTransformationOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.logging.Logger;

/**
 * Filters console output to check for size limits.
 */
@Extension
public class LogSizeKillerConsoleLogFilter extends ConsoleLogFilter implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(LogSizeKillerConsoleLogFilter.class.getName());

    @Override
    public OutputStream decorateLogger(Run build, OutputStream logger) throws IOException, InterruptedException {
        LogSizeKillerGlobalConfiguration config = LogSizeKillerGlobalConfiguration.get();
        if (config == null || !config.isEnabled()) {
            return logger;
        }

        return new SizeCountingOutputStream(logger, build, config.getMaxLogSize());
    }

    private static class SizeCountingOutputStream extends LineTransformationOutputStream {
        private final OutputStream original;
        private final Run<?, ?> build;
        private final long limit;
        private long currentSize = 0;
        private boolean killed = false;

        SizeCountingOutputStream(OutputStream original, Run<?, ?> build, long limit) {
            this.original = original;
            this.build = build;
            this.limit = limit;
        }

        @Override
        protected void eol(byte[] b, int len) throws IOException {
            if (killed) {
                original.write(b, 0, len);
                return;
            }

            currentSize += len;
            if (limit > 0 && currentSize > limit) {
                killed = true;
                String msg = String.format("\n[LogSizeKiller] Build log exceeded limit of %d bytes. Aborting build.\n", limit);
                original.write(msg.getBytes());
                
                // Abort the build
                Executor executor = build.getExecutor();
                if (executor != null) {
                    executor.interrupt(Result.ABORTED);
                }
            }
            
            original.write(b, 0, len);
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                original.close();
            }
        }
        
        @Override
        public void flush() throws IOException {
            original.flush();
        }
    }
}

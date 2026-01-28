package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.Executor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Filters console output to check for size limits.
 * Uses a direct OutputStream wrapper to count every byte, ensuring it works even for 
 * very long lines or when newlines are rare (common in some Windows build tools).
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

        long limit = config.getMaxLogSize();
        if (limit <= 0) {
            LOGGER.log(Level.FINE, "LogSizeKiller is enabled but limit is 0 or negative for build {0}", build.getFullDisplayName());
            return logger;
        }

        LOGGER.log(Level.FINE, "Decorating logger for build {0} with limit {1} bytes", new Object[]{build.getFullDisplayName(), limit});
        return new SizeCountingOutputStream(logger, build, limit);
    }

    /**
     * A simple wrapper that counts every byte written to the stream.
     * This avoids the "no newline" issues with LineTransformationOutputStream.
     */
    private static class SizeCountingOutputStream extends OutputStream implements Serializable {
        private static final long serialVersionUID = 1L;
        private final OutputStream out;
        private final Run<?, ?> build;
        private final long limit;
        private long currentSize = 0;
        private boolean killed = false;

        SizeCountingOutputStream(OutputStream out, Run<?, ?> build, long limit) {
            this.out = out;
            this.build = build;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count(1);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
            count(b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count(len);
        }

        private synchronized void count(int len) throws IOException {
            if (killed) return;
            currentSize += len;

            if (currentSize > limit) {
                killed = true;
                
                LOGGER.log(Level.INFO, "Log limit reached for {0}: {1} > {2}. Initiating abort.", 
                    new Object[]{build.getFullDisplayName(), currentSize, limit});

                // Print alert to the log immediately
                String msg = String.format("\n[LogSizeKiller] Build log exceeded limit of %d bytes. Aborting build.\n", limit);
                try {
                    out.write(msg.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Failed to write kill message to console for " + build.getFullDisplayName(), e);
                }

                // Interrupt the build in a separate thread to avoid blocking the write
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        Executor executor = build.getExecutor();
                        if (executor != null) {
                            executor.interrupt(Result.ABORTED);
                        } else {
                            LOGGER.log(Level.WARNING, "Could not abort build {0}: No executor found", build.getFullDisplayName());
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to abort build " + build.getFullDisplayName(), e);
                    }
                }, "LogSizeKiller-Abort-" + build.getFullDisplayName()).start();
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            LOGGER.log(Level.FINE, "Closing SizeCountingOutputStream for {0}. Total size: {1} bytes", 
                new Object[]{build.getFullDisplayName(), currentSize});
            out.close();
        }
    }
}

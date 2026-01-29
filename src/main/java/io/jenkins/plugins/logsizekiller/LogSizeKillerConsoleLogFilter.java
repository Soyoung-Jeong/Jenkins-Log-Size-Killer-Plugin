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

    /**
     * A simple wrapper that counts every byte written to the stream.
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
                
                String msg = String.format("\n[LogSizeKiller] Build log exceeded limit of %d bytes. Aborting build.\n", limit);
                try {
                    out.write(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // Ignore
                }

                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        hudson.model.Executor executor = build.getExecutor();
                        if (executor != null) {
                            executor.interrupt(hudson.model.Result.ABORTED);
                        }
                    } catch (Exception e) {
                        // Ignore
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
            out.close();
        }
    }
}

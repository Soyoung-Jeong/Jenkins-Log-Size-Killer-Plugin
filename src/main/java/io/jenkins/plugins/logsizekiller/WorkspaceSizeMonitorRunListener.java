package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.remoting.VirtualChannel;
import jenkins.security.MasterToSlaveCallable;
import jenkins.util.Timer;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class WorkspaceSizeMonitorRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceSizeMonitorRunListener.class.getName());
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        LogSizeKillerGlobalConfiguration config = LogSizeKillerGlobalConfiguration.get();
        if (config == null || !config.isEnabled() || config.getMaxWorkspaceSize() <= 0) {
            return;
        }

        long maxBytes = config.getMaxWorkspaceSize();
        int interval = config.getCheckIntervalSeconds();
        if (interval < 10) interval = 10; // Minimum safety interval

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (!run.isBuilding()) {
                    cancelTask(run);
                    return;
                }

                try {
                    FilePath workspace = null;
                    if (run instanceof hudson.model.AbstractBuild) {
                        workspace = ((hudson.model.AbstractBuild<?, ?>) run).getWorkspace();
                    }

                    if (workspace != null) {
                        long size = workspace.act(new GetDirectorySize());
                        if (size > maxBytes) {
                            listener.getLogger().println("[LogSizeKiller] Workspace size " + size + " bytes exceeded limit " + maxBytes + ". Aborting build.");
                            run.getExecutor().interrupt(Result.ABORTED);
                            cancelTask(run);
                        }
                    }

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to check workspace size for " + run.getFullDisplayName(), e);
                }
            }
        };

        ScheduledFuture<?> task = Timer.get().scheduleAtFixedRate(checkTask, interval, interval, TimeUnit.SECONDS);
        tasks.put(run.getExternalizableId(), task);
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        cancelTask(run);
    }
    
    @Override
    public void onFinalized(Run<?, ?> run) {
        cancelTask(run);
    }

    private void cancelTask(Run<?, ?> run) {
        ScheduledFuture<?> task = tasks.remove(run.getExternalizableId());
        if (task != null) {
            task.cancel(true);
        }
    }
    
    private static class GetDirectorySize implements hudson.FilePath.FileCallable<Long> {
        private static final long serialVersionUID = 1L;
        @Override
        public Long invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            final AtomicLong size = new AtomicLong(0);
            if (!f.exists()) return 0L;
            
            Files.walkFileTree(f.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
            return size.get();
        }

        @Override
        public void checkRoles(hudson.remoting.RoleChecker checker) throws SecurityException {
            // unrestricted
        }
    }
}

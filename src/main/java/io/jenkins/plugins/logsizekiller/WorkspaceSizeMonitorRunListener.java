package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Executor;
import hudson.model.listeners.RunListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
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
        int interval = Math.max(config.getCheckIntervalSeconds(), 10);

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (!run.isBuilding()) {
                    cancelTask(run);
                    return;
                }

                try {
                    // Try to get workspace from executor (works for both Freestyle and Pipeline)
                    Executor executor = run.getExecutor();
                    FilePath workspace = (executor != null) ? executor.getCurrentWorkspace() : null;

                    // Fallback for Freestyle projects
                    if (workspace == null && run instanceof hudson.model.AbstractBuild) {
                        workspace = ((hudson.model.AbstractBuild<?, ?>) run).getWorkspace();
                    }

                    if (workspace != null && workspace.exists()) {
                        long size = workspace.act(new GetDirectorySize());
                        if (size > maxBytes) {
                            listener.getLogger().println("[LogSizeKiller] Workspace size " + size + " bytes exceeded limit " + maxBytes + ". Aborting build.");
                            if (executor != null) {
                                executor.interrupt(Result.ABORTED);
                            }
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
            task.cancel(false);
        }
    }
    
    /**
     * Callable that runs on the agent (Linux/Windows) to calculate directory size.
     */
    private static class GetDirectorySize extends MasterToSlaveFileCallable<Long> {
        private static final long serialVersionUID = 1L;
        
        @Override
        public Long invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            final AtomicLong totalSize = new AtomicLong(0);
            if (!f.exists() || !f.isDirectory()) return 0L;
            
            Files.walkFileTree(f.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    totalSize.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // On Windows, some files might be locked. We skip them to avoid failing the whole check.
                    return FileVisitResult.CONTINUE;
                }
            });
            return totalSize.get();
        }
    }
}

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

        LOGGER.log(Level.FINE, "Scheduling workspace size monitor for {0} (limit: {1}, interval: {2}s)", 
            new Object[]{run.getFullDisplayName(), maxBytes, interval});

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (!run.isBuilding()) {
                    LOGGER.log(Level.FINE, "Build {0} is no longer building. Cancelling monitor.", run.getFullDisplayName());
                    cancelTask(run);
                    return;
                }

                try {
                    Executor executor = run.getExecutor();
                    FilePath workspace = (executor != null) ? executor.getCurrentWorkspace() : null;

                    if (workspace == null && run instanceof hudson.model.AbstractBuild) {
                        workspace = ((hudson.model.AbstractBuild<?, ?>) run).getWorkspace();
                    }

                    if (workspace != null && workspace.exists()) {
                        LOGGER.log(Level.FINER, "Checking workspace size for {0} at {1}", 
                            new Object[]{run.getFullDisplayName(), workspace.getRemote()});
                        
                        long size = workspace.act(new GetDirectorySize(run.getFullDisplayName()));
                        
                        LOGGER.log(Level.FINER, "Workspace size for {0}: {1} bytes", 
                            new Object[]{run.getFullDisplayName(), size});

                        if (size > maxBytes) {
                            LOGGER.log(Level.INFO, "Workspace limit reached for {0}: {1} > {2}. Aborting.", 
                                new Object[]{run.getFullDisplayName(), size, maxBytes});
                            
                            listener.getLogger().println("[LogSizeKiller] Workspace size " + size + " bytes exceeded limit " + maxBytes + ". Aborting build.");
                            if (executor != null) {
                                executor.interrupt(Result.ABORTED);
                            }
                            cancelTask(run);
                        }
                    } else if (workspace != null) {
                        LOGGER.log(Level.FINE, "Workspace at {0} does not exist yet for {1}", 
                            new Object[]{workspace.getRemote(), run.getFullDisplayName()});
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
            LOGGER.log(Level.FINE, "Cancelled workspace monitor for {0}", run.getFullDisplayName());
            task.cancel(false);
        }
    }
    
    /**
     * Callable that runs on the agent (Linux/Windows) to calculate directory size.
     */
    private static class GetDirectorySize extends MasterToSlaveFileCallable<Long> {
        private static final long serialVersionUID = 1L;
        private final String buildName;

        GetDirectorySize(String buildName) {
            this.buildName = buildName;
        }
        
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
                    // Log at a fine level that a file was skipped (likely locked on Windows)
                    // Note: We use a local Logger or static Logger here as this runs on the agent.
                    // Agents have their own logger hierarchy.
                    Logger.getLogger(WorkspaceSizeMonitorRunListener.class.getName())
                          .log(Level.FINE, "Skipping file {0} for build {1} due to error: {2}", 
                               new Object[]{file.toString(), buildName, exc.getMessage()});
                    return FileVisitResult.CONTINUE;
                }
            });
            return totalSize.get();
        }
    }
}

package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Executor;
import hudson.model.User;
import hudson.model.listeners.RunListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import jenkins.util.Timer;
import jenkins.security.NotReallyRoleSensitiveCallable;

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
public class ResourceMonitorRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ResourceMonitorRunListener.class.getName());
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        LogSizeKillerGlobalConfiguration config = LogSizeKillerGlobalConfiguration.get();
        if (config == null || !config.isEnabled()) {
            return;
        }

        // If both limits are disabled, do nothing
        if (config.getMaxLogSize() <= 0 && config.getMaxWorkspaceSize() <= 0) {
            return;
        }

        long logLimit = config.getMaxLogSize();
        long wsLimit = config.getMaxWorkspaceSize();
        int interval = Math.max(config.getCheckIntervalSeconds(), 10);

        LOGGER.log(Level.FINE, "Scheduling resource monitor for {0} (LogLimit: {1}, WsLimit: {2}, Interval: {3}s)", 
            new Object[]{run.getFullDisplayName(), logLimit, wsLimit, interval});

        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (!run.isBuilding()) {
                    cancelTask(run);
                    return;
                }

                try {
                    // 1. Check Log Size via File (works for all Project types including Pipeline)
                    if (logLimit > 0) {
                        File logFile = run.getLogFile();
                        if (logFile != null && logFile.exists()) {
                            long logSize = logFile.length();
                            if (logSize > logLimit) {
                                LOGGER.log(Level.INFO, "Log limit exceeded for {0}: {1} > {2}", 
                                    new Object[]{run.getFullDisplayName(), logSize, logLimit});
                                
                                // Try to write to the log before killing
                                try {
                                    // Note: This approach appends to the log file on master, which is safe.
                                    // Use a temporary listener or append to the existing one? Use global logger for now or try to append.
                                    // Actually, just killing is fine, the reason will be in the abort cause.
                                    // Better:
                                    listener.getLogger().println("\n[LogSizeKiller] Build log exceeded limit of " + logLimit + " bytes. Aborting build.\n");
                                } catch (Exception ignore) {}
                                
                                kill(run, listener);
                                cancelTask(run);
                                return; // Stop checking workspace if killed
                            }
                        }
                    }

                    // 2. Check Workspace Size
                    if (wsLimit > 0) {
                        checkWorkspaceSize(run, listener, wsLimit);
                    }

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Global resource check failed for " + run.getFullDisplayName(), e);
                }
            }
        };

        ScheduledFuture<?> task = Timer.get().scheduleAtFixedRate(checkTask, interval, interval, TimeUnit.SECONDS);
        tasks.put(run.getExternalizableId(), task);
    }

    private void checkWorkspaceSize(Run<?, ?> run, TaskListener listener, long maxBytes) {
        try {
            // Best effort for Freestyle and simple Pipeline
            Executor executor = run.getExecutor();
            FilePath workspace = (executor != null) ? executor.getCurrentWorkspace() : null;

            if (workspace == null && run instanceof hudson.model.AbstractBuild) {
                workspace = ((hudson.model.AbstractBuild<?, ?>) run).getWorkspace();
            }

            // NOTE: For complex Pipelines with multiple agents, this listener might not see all workspaces.
            // This generally works for the 'main' workspace or the node running the current chunk if visible.
            
            if (workspace != null && workspace.exists()) {
                long size = workspace.act(new GetDirectorySize(run.getFullDisplayName()));
                if (size > maxBytes) {
                    listener.getLogger().println("[LogSizeKiller] Workspace size " + size + " bytes exceeded limit " + maxBytes + ". Aborting build.");
                    kill(run, listener);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to check workspace size for " + run.getFullDisplayName(), e);
        }
    }

    private void kill(Run<?, ?> run, TaskListener listener) {
        Executor executor = run.getExecutor();
        if (executor != null) {
            executor.interrupt(Result.ABORTED, 
                new jenkins.model.CauseOfInterruption.UserInterruption("LogSizeKiller Plugin"));
        } else {
            // For Pipeline, sometimes getExecutor is null on the controller side while running
            // We can try to abort the FlowExecution if available
             if (run instanceof org.jenkinsci.plugins.workflow.job.WorkflowRun) {
                 try {
                     org.jenkinsci.plugins.workflow.job.WorkflowRun wfr = (org.jenkinsci.plugins.workflow.job.WorkflowRun) run;
                     if (wfr.getExecution() != null) {
                         // interrupting the execution
                         wfr.getExecutor().interrupt(Result.ABORTED);
                         // Logic above might be redundant but safe.
                         // Actually, standard Run.doStop() logic might be better but we want 'Aborted'.
                     }
                 } catch (Exception e) {
                     LOGGER.log(Level.WARNING, "Failed to interrupt Pipeline execution for " + run.getFullDisplayName(), e);
                 }
             }
        }
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
    
    // Using simple MasterToSlaveFileCallable
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
                    return FileVisitResult.CONTINUE;
                }
            });
            return totalSize.get();
        }
    }
}

package io.jenkins.plugins.logsizekiller;

import hudson.console.ConsoleLogFilter;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import java.io.Serializable;

public class LogSizeLimitStepExecution extends StepExecution {

    private static final long serialVersionUID = 1L;
    private final LogSizeLimitStep step;

    public LogSizeLimitStepExecution(LogSizeLimitStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        long limitBytes = step.getSize() * 1024 * 1024;
        ConsoleLogFilter filter = new LogSizeKillerConsoleLogFilter(limitBytes);
        
        getContext().newBodyInvoker()
            .withContext(BodyInvoker.mergeConsoleLogFilters(getContext().get(ConsoleLogFilter.class), filter))
            .withCallback(BodyExecutionCallback.wrap(getContext()))
            .start();
            
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // usually nothing to do here
    }
}

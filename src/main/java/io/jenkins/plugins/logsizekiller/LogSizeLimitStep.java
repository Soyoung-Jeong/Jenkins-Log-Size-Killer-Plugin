package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collections;
import java.util.Set;

public class LogSizeLimitStep extends Step {

    private final long size; // in MB

    @DataBoundConstructor
    public LogSizeLimitStep(long size) {
        this.size = size;
    }

    public long getSize() {
        return size;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new LogSizeLimitStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "logSizeLimit";
        }

        @Override
        public String getDisplayName() {
            return "Enforce Log Size Limit";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }
        
        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }
    }
}

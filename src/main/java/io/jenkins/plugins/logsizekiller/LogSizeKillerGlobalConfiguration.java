package io.jenkins.plugins.logsizekiller;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Global configuration for Log Size Killer Plugin.
 */
@Extension
public class LogSizeKillerGlobalConfiguration extends GlobalConfiguration {

    public static LogSizeKillerGlobalConfiguration get() {
        return GlobalConfiguration.all().get(LogSizeKillerGlobalConfiguration.class);
    }

    private boolean enabled;
    private long maxLogSize = 10 * 1024 * 1024; // Default 10MB
    /**
     * Max workspace size in bytes. 0 means disabled.
     */
    private long maxWorkspaceSize = 0; 
    
    private int checkIntervalSeconds = 60; // Default 60 seconds

    public LogSizeKillerGlobalConfiguration() {
        load();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public long getMaxLogSize() {
        return maxLogSize;
    }

    public void setMaxLogSize(long maxLogSize) {
        this.maxLogSize = maxLogSize;
        save();
    }
    
    public long getMaxWorkspaceSize() {
        return maxWorkspaceSize;
    }

    public void setMaxWorkspaceSize(long maxWorkspaceSize) {
        this.maxWorkspaceSize = maxWorkspaceSize;
        save();
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(int checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
        save();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }
}

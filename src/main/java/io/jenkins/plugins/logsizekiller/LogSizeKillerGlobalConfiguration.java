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
    private long maxLogSizeMB = 10; // Default 10MB
    /**
     * Max workspace size in MB. 0 means disabled.
     */
    private long maxWorkspaceSizeMB = 0; 
    
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

    public long getMaxLogSizeMB() {
        return maxLogSizeMB;
    }

    public void setMaxLogSizeMB(long maxLogSizeMB) {
        this.maxLogSizeMB = maxLogSizeMB;
        save();
    }
    
    // Helper method for internal use
    public long getMaxLogSizeBytes() {
        return maxLogSizeMB * 1024 * 1024;
    }

    public long getMaxWorkspaceSizeMB() {
        return maxWorkspaceSizeMB;
    }

    public void setMaxWorkspaceSizeMB(long maxWorkspaceSizeMB) {
        this.maxWorkspaceSizeMB = maxWorkspaceSizeMB;
        save();
    }
    
    // Helper method for internal use
    public long getMaxWorkspaceSizeBytes() {
        return maxWorkspaceSizeMB * 1024 * 1024;
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

package io.jenkins.plugins.pipelineoverview.model;

import net.sf.json.JSONObject;

/**
 * Describes a pipeline that is currently in a failed state, including
 * which stage failed, when the failure streak started, and how many
 * consecutive builds have failed.
 */
public class FailureInfo {

    private final String jobName;
    private final String displayName;
    private final String groupName;
    private final String failedStage;   // may be null if unknown
    private final long failedSinceMs;
    private final int buildNumber;
    private final String buildUrl;
    private final int consecutiveFailures;

    public FailureInfo(
            String jobName, String displayName, String groupName,
            String failedStage, long failedSinceMs,
            int buildNumber, String buildUrl,
            int consecutiveFailures) {
        this.jobName = jobName;
        this.displayName = displayName;
        this.groupName = groupName;
        this.failedStage = failedStage;
        this.failedSinceMs = failedSinceMs;
        this.buildNumber = buildNumber;
        this.buildUrl = buildUrl;
        this.consecutiveFailures = consecutiveFailures;
    }

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("jobName", jobName);
        o.put("displayName", displayName);
        o.put("groupName", groupName);
        o.put("failedStage", failedStage != null ? failedStage : "");
        o.put("failedSinceMs", failedSinceMs);
        o.put("buildNumber", buildNumber);
        o.put("buildUrl", buildUrl);
        o.put("consecutiveFailures", consecutiveFailures);
        return o;
    }
}

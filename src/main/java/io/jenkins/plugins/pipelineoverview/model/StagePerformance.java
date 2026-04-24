package io.jenkins.plugins.pipelineoverview.model;

import net.sf.json.JSONObject;

/**
 * Captures average and maximum duration for one stage of one pipeline,
 * used to surface the slowest pipeline stages.
 */
public class StagePerformance {

    private final String jobName;
    private final String displayName;
    private final String stageName;
    private final long avgDurationMs;
    private final long maxDurationMs;
    private final int sampleSize;

    public StagePerformance(
            String jobName, String displayName, String stageName,
            long avgDurationMs, long maxDurationMs, int sampleSize) {
        this.jobName = jobName;
        this.displayName = displayName;
        this.stageName = stageName;
        this.avgDurationMs = avgDurationMs;
        this.maxDurationMs = maxDurationMs;
        this.sampleSize = sampleSize;
    }

    public long getAvgDurationMs() { return avgDurationMs; }

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("jobName", jobName);
        o.put("displayName", displayName);
        o.put("stageName", stageName);
        o.put("avgDurationMs", avgDurationMs);
        o.put("maxDurationMs", maxDurationMs);
        o.put("sampleSize", sampleSize);
        return o;
    }
}

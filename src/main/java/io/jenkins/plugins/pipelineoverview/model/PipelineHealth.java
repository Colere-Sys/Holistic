package io.jenkins.plugins.pipelineoverview.model;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Aggregated health data for a single pipeline over a configurable
 * history window.  Drives the health grid, sparklines, and KPI cards.
 */
public class PipelineHealth {

    private final String jobName;
    private final String displayName;
    private final String groupName;
    private final String lastStatus;   // SUCCESS, FAILURE, UNSTABLE, ABORTED, BUILDING, NO_BUILDS, NOT_FOUND, NOT_A_PIPELINE
    private final int lastBuildNumber;
    private final String lastBuildUrl;
    private final long lastBuildTimeMs;
    private final double successRate7d;
    private final double successRate30d;
    private final int totalBuilds7d;
    private final int totalBuilds30d;
    private final long avgDurationMs;
    private final String durationTrend; // IMPROVING, DEGRADING, STABLE
    private final String successTrend;  // IMPROVING, DEGRADING, STABLE
    private final int[] dailyBuilds;           // last 14 days, index 0 = oldest
    private final double[] dailySuccessRate;   // last 14 days, index 0 = oldest
    private final boolean building;

    public PipelineHealth(
            String jobName, String displayName, String groupName,
            String lastStatus, int lastBuildNumber, String lastBuildUrl,
            long lastBuildTimeMs,
            double successRate7d, double successRate30d,
            int totalBuilds7d, int totalBuilds30d,
            long avgDurationMs,
            String durationTrend, String successTrend,
            int[] dailyBuilds, double[] dailySuccessRate,
            boolean building) {
        this.jobName = jobName;
        this.displayName = displayName;
        this.groupName = groupName;
        this.lastStatus = lastStatus;
        this.lastBuildNumber = lastBuildNumber;
        this.lastBuildUrl = lastBuildUrl;
        this.lastBuildTimeMs = lastBuildTimeMs;
        this.successRate7d = successRate7d;
        this.successRate30d = successRate30d;
        this.totalBuilds7d = totalBuilds7d;
        this.totalBuilds30d = totalBuilds30d;
        this.avgDurationMs = avgDurationMs;
        this.durationTrend = durationTrend;
        this.successTrend = successTrend;
        this.dailyBuilds = dailyBuilds;
        this.dailySuccessRate = dailySuccessRate;
        this.building = building;
    }

    /* ---- accessors used by the service layer ---- */

    public String getLastStatus()    { return lastStatus; }
    public int getTotalBuilds7d()    { return totalBuilds7d; }
    public int getTotalBuilds30d()   { return totalBuilds30d; }
    public long getAvgDurationMs()   { return avgDurationMs; }
    public long getLastBuildTimeMs() { return lastBuildTimeMs; }
    public String getGroupName()     { return groupName; }
    public int[] getDailyBuilds()    { return dailyBuilds; }
    public double[] getDailySuccessRate() { return dailySuccessRate; }

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("jobName", jobName);
        o.put("displayName", displayName);
        o.put("groupName", groupName);
        o.put("lastStatus", lastStatus);
        o.put("lastBuildNumber", lastBuildNumber);
        o.put("lastBuildUrl", lastBuildUrl);
        o.put("lastBuildTimeMs", lastBuildTimeMs);
        o.put("successRate7d", round(successRate7d));
        o.put("successRate30d", round(successRate30d));
        o.put("totalBuilds7d", totalBuilds7d);
        o.put("totalBuilds30d", totalBuilds30d);
        o.put("avgDurationMs", avgDurationMs);
        o.put("durationTrend", durationTrend);
        o.put("successTrend", successTrend);
        o.put("building", building);

        JSONArray db = new JSONArray();
        for (int v : dailyBuilds) db.add(v);
        o.put("dailyBuilds", db);

        JSONArray ds = new JSONArray();
        for (double v : dailySuccessRate) ds.add(round(v));
        o.put("dailySuccessRate", ds);

        return o;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

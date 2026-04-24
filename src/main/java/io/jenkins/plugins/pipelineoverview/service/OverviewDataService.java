package io.jenkins.plugins.pipelineoverview.service;

import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.pipelineoverview.DashboardEntry;
import io.jenkins.plugins.pipelineoverview.DashboardGroup;
import io.jenkins.plugins.pipelineoverview.model.FailureInfo;
import io.jenkins.plugins.pipelineoverview.model.PipelineHealth;
import io.jenkins.plugins.pipelineoverview.model.StagePerformance;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aggregates build history, computes success rates, trends, failure
 * streaks, and stage performance data for all configured pipelines.
 * <p>
 * Build records are cached per-pipeline with a short TTL to avoid
 * repeatedly scanning Jenkins build directories when multiple users
 * (or the auto-refresh) hit the dashboard in quick succession.
 */
public class OverviewDataService {

    private static final Logger LOGGER =
            Logger.getLogger(OverviewDataService.class.getName());

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int SPARKLINE_DAYS = 14;
    private static final int MAX_BUILDS_PER_PIPELINE = 200;
    private static final int STAGE_SAMPLE_BUILDS = 5;
    private static final int TOP_SLOW_STAGES = 5;

    /* ---------- per-pipeline build-record cache ---------- */

    private static final Map<String, CachedBuilds> BUILD_CACHE = new ConcurrentHashMap<>();
    private static final long BUILD_CACHE_TTL_MS = 15_000;

    /* ---------- per-build stage cache (completed only) --- */

    private static final Map<String, List<StageRecord>> STAGE_CACHE = new ConcurrentHashMap<>();

    /* ===================================================== */

    /**
     * Produces the full JSON payload consumed by the frontend.
     */
    public JSONObject fetchDashboardData(List<DashboardGroup> groups, int historyDays) {
        Jenkins jenkins = Jenkins.get();
        long now = System.currentTimeMillis();

        List<PipelineHealth> healths = new ArrayList<>();
        List<FailureInfo> failures = new ArrayList<>();
        /* job:stage -> list of durations */
        Map<String, StageDurationAccumulator> stageAccum = new LinkedHashMap<>();

        int buildsToday = 0;
        int buildsThisWeek = 0;
        long durationSum = 0;
        int durationCount = 0;

        long todayStart = startOfDay(now);
        long weekStart = todayStart - 6 * DAY_MS;
        long sevenDaysAgo = now - 7 * DAY_MS;

        for (DashboardGroup group : groups) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (!entry.isEnabled()) continue;

                PipelineHealth health = processPipeline(
                        jenkins, group.getName(), entry, now, historyDays);
                if (health == null) continue;   // permission denied
                healths.add(health);

                /* aggregate KPIs */
                for (int v : health.getDailyBuilds()) {
                    /* dailyBuilds is 14 days; today is index 13 */
                }
                buildsToday += health.getDailyBuilds()[SPARKLINE_DAYS - 1];
                for (int i = Math.max(0, SPARKLINE_DAYS - 7); i < SPARKLINE_DAYS; i++) {
                    buildsThisWeek += health.getDailyBuilds()[i];
                }
                if (health.getAvgDurationMs() > 0) {
                    durationSum += health.getAvgDurationMs();
                    durationCount++;
                }

                /* current failures */
                if ("FAILURE".equals(health.getLastStatus())) {
                    failures.add(buildFailureInfo(jenkins, group.getName(), entry, now, historyDays));
                }

                /* stage performance (sample recent successful builds) */
                collectStagePerformance(jenkins, entry, stageAccum, now, historyDays);
            }
        }

        /* ---------- top slowest stages ---------- */
        List<StagePerformance> slowStages = computeTopSlowStages(stageAccum, TOP_SLOW_STAGES);

        /* ---------- stale pipelines ---------- */
        long staleThreshold = now - 7 * DAY_MS;
        JSONArray stalePipelines = new JSONArray();
        for (PipelineHealth h : healths) {
            if (h.getLastBuildTimeMs() > 0 && h.getLastBuildTimeMs() < staleThreshold
                    && !"NO_BUILDS".equals(h.getLastStatus())
                    && !"NOT_FOUND".equals(h.getLastStatus())
                    && !"NOT_A_PIPELINE".equals(h.getLastStatus())) {
                JSONObject sp = new JSONObject();
                sp.put("jobName", h.toJSON().get("jobName"));
                sp.put("displayName", h.toJSON().get("displayName"));
                sp.put("lastBuildTimeMs", h.getLastBuildTimeMs());
                sp.put("daysSinceLastBuild",
                        (int) ((now - h.getLastBuildTimeMs()) / DAY_MS));
                stalePipelines.add(sp);
            }
        }

        /* ---------- infrastructure ---------- */
        int queueSize = jenkins.getQueue().getItems().length;
        int busyExecutors = 0, totalExecutors = 0;
        for (Computer c : jenkins.getComputers()) {
            if (c.isOnline()) {
                totalExecutors += c.getNumExecutors();
                busyExecutors += c.countBusy();
            }
        }

        /* ---------- overall success rate (7d) ---------- */
        int total7d = 0, success7d = 0;
        for (PipelineHealth h : healths) {
            total7d += h.getTotalBuilds7d();
            /* approximate by rate * count */
            for (int i = Math.max(0, SPARKLINE_DAYS - 7); i < SPARKLINE_DAYS; i++) {
                int db = h.getDailyBuilds()[i];
                double sr = h.getDailySuccessRate()[i];
                success7d += (int) Math.round(db * sr / 100.0);
            }
        }
        double overallSuccessRate = total7d > 0 ? (success7d * 100.0 / total7d) : 100.0;

        /* ---------- aggregate daily sparklines ---------- */
        int[] aggDailyBuilds = new int[SPARKLINE_DAYS];
        double[] aggDailySuccess = new double[SPARKLINE_DAYS];
        int[] aggDailyTotal = new int[SPARKLINE_DAYS];
        int[] aggDailySuccessCount = new int[SPARKLINE_DAYS];
        for (PipelineHealth h : healths) {
            for (int i = 0; i < SPARKLINE_DAYS; i++) {
                aggDailyBuilds[i] += h.getDailyBuilds()[i];
                int db = h.getDailyBuilds()[i];
                aggDailyTotal[i] += db;
                aggDailySuccessCount[i] += (int) Math.round(db * h.getDailySuccessRate()[i] / 100.0);
            }
        }
        for (int i = 0; i < SPARKLINE_DAYS; i++) {
            aggDailySuccess[i] = aggDailyTotal[i] > 0
                    ? (aggDailySuccessCount[i] * 100.0 / aggDailyTotal[i]) : 100.0;
        }

        /* ---------- count statuses ---------- */
        int healthyCount = 0, failingCount = 0, unstableCount = 0, activePipelines = 0;
        for (PipelineHealth h : healths) {
            String s = h.getLastStatus();
            if ("NOT_FOUND".equals(s) || "NOT_A_PIPELINE".equals(s) || "NO_BUILDS".equals(s)) continue;
            activePipelines++;
            if ("SUCCESS".equals(s)) healthyCount++;
            else if ("FAILURE".equals(s)) failingCount++;
            else if ("UNSTABLE".equals(s)) unstableCount++;
        }

        /* ========== build JSON response ========== */
        JSONObject result = new JSONObject();
        result.put("timestamp", now);

        JSONObject summary = new JSONObject();
        summary.put("totalPipelines", activePipelines);
        summary.put("healthyPipelines", healthyCount);
        summary.put("failingPipelines", failingCount);
        summary.put("unstablePipelines", unstableCount);
        summary.put("overallSuccessRate", Math.round(overallSuccessRate * 10.0) / 10.0);
        summary.put("buildsToday", buildsToday);
        summary.put("buildsThisWeek", buildsThisWeek);
        summary.put("avgBuildDurationMs",
                durationCount > 0 ? durationSum / durationCount : 0);
        summary.put("queueSize", queueSize);
        summary.put("busyExecutors", busyExecutors);
        summary.put("totalExecutors", totalExecutors);

        JSONArray aggDB = new JSONArray();
        for (int v : aggDailyBuilds) aggDB.add(v);
        summary.put("dailyBuilds", aggDB);
        JSONArray aggDS = new JSONArray();
        for (double v : aggDailySuccess) aggDS.add(Math.round(v * 10.0) / 10.0);
        summary.put("dailySuccessRate", aggDS);

        result.put("summary", summary);

        JSONArray pArr = new JSONArray();
        for (PipelineHealth h : healths) pArr.add(h.toJSON());
        result.put("pipelines", pArr);

        JSONArray fArr = new JSONArray();
        for (FailureInfo f : failures) fArr.add(f.toJSON());
        result.put("currentFailures", fArr);

        JSONArray sArr = new JSONArray();
        for (StagePerformance s : slowStages) sArr.add(s.toJSON());
        result.put("slowestStages", sArr);

        result.put("stalePipelines", stalePipelines);

        evictStaleCache();
        return result;
    }

    /* ===================================================== */
    /*  Per-pipeline processing                              */
    /* ===================================================== */

    private PipelineHealth processPipeline(
            Jenkins jenkins, String groupName, DashboardEntry entry,
            long now, int historyDays) {

        String jobFullName = entry.getJobName();
        String displayName = entry.getEffectiveDisplayName();

        Item item = jenkins.getItemByFullName(jobFullName);
        if (item == null) {
            return placeholderHealth(jobFullName, displayName, groupName, "NOT_FOUND");
        }
        if (!item.hasPermission(Item.READ)) {
            return null;
        }
        if (!(item instanceof WorkflowJob)) {
            return placeholderHealth(jobFullName, displayName, groupName, "NOT_A_PIPELINE");
        }

        WorkflowJob job = (WorkflowJob) item;
        List<BuildRecord> records = fetchBuildRecords(job, now, historyDays);

        if (records.isEmpty()) {
            return placeholderHealth(jobFullName, displayName, groupName, "NO_BUILDS");
        }

        /* ---- latest build ---- */
        BuildRecord latest = records.get(0);
        String lastStatus = latest.building ? "BUILDING" : latest.result;
        String lastBuildUrl = buildAbsoluteUrl(job, latest.number);

        /* ---- daily buckets (14 days) ---- */
        int[] dailyBuilds = new int[SPARKLINE_DAYS];
        int[] dailySuccess = new int[SPARKLINE_DAYS];
        int total7d = 0, success7d = 0, total30d = 0, success30d = 0;
        long durationSum = 0;
        int durationCount = 0;
        long sevenDaysAgo = now - 7 * DAY_MS;
        long thirtyDaysAgo = now - 30 * DAY_MS;

        for (BuildRecord r : records) {
            if (r.building) continue;

            /* sparkline bucket */
            int daysAgo = (int) ((now - r.startTimeMs) / DAY_MS);
            if (daysAgo >= 0 && daysAgo < SPARKLINE_DAYS) {
                int idx = SPARKLINE_DAYS - 1 - daysAgo;
                dailyBuilds[idx]++;
                if ("SUCCESS".equals(r.result)) dailySuccess[idx]++;
            }

            /* 7-day window */
            if (r.startTimeMs >= sevenDaysAgo) {
                total7d++;
                if ("SUCCESS".equals(r.result)) success7d++;
            }

            /* 30-day window */
            if (r.startTimeMs >= thirtyDaysAgo) {
                total30d++;
                if ("SUCCESS".equals(r.result)) success30d++;
            }

            /* duration (exclude very short builds < 1s as likely aborted) */
            if (r.durationMs > 1000) {
                durationSum += r.durationMs;
                durationCount++;
            }
        }

        double[] dailySuccessRate = new double[SPARKLINE_DAYS];
        for (int i = 0; i < SPARKLINE_DAYS; i++) {
            dailySuccessRate[i] = dailyBuilds[i] > 0
                    ? (dailySuccess[i] * 100.0 / dailyBuilds[i]) : 100.0;
        }

        double successRate7d = total7d > 0 ? (success7d * 100.0 / total7d) : 100.0;
        double successRate30d = total30d > 0 ? (success30d * 100.0 / total30d) : 100.0;
        long avgDuration = durationCount > 0 ? durationSum / durationCount : 0;

        /* ---- trends ---- */
        String successTrend = computeTrend(dailySuccessRate);
        String durationTrend = computeDurationTrend(records, now);

        return new PipelineHealth(
                jobFullName, displayName, groupName,
                lastStatus, latest.number, lastBuildUrl,
                latest.startTimeMs,
                successRate7d, successRate30d,
                total7d, total30d,
                avgDuration,
                durationTrend, successTrend,
                dailyBuilds, dailySuccessRate,
                latest.building);
    }

    /* ===================================================== */
    /*  Failure info                                         */
    /* ===================================================== */

    private FailureInfo buildFailureInfo(
            Jenkins jenkins, String groupName, DashboardEntry entry,
            long now, int historyDays) {

        String jobFullName = entry.getJobName();
        String displayName = entry.getEffectiveDisplayName();
        Item item = jenkins.getItemByFullName(jobFullName);
        if (!(item instanceof WorkflowJob)) {
            return new FailureInfo(jobFullName, displayName, groupName,
                    null, now, 0, "", 1);
        }

        WorkflowJob job = (WorkflowJob) item;
        List<BuildRecord> records = fetchBuildRecords(job, now, historyDays);

        /* count consecutive failures */
        int consecutive = 0;
        long failedSince = now;
        int failedBuildNumber = 0;
        for (BuildRecord r : records) {
            if (r.building) continue;
            if ("FAILURE".equals(r.result)) {
                consecutive++;
                failedSince = r.startTimeMs;
                if (failedBuildNumber == 0) failedBuildNumber = r.number;
            } else {
                break;
            }
        }

        /* find which stage failed in the latest failed build */
        String failedStage = findFailedStage(job, failedBuildNumber);

        return new FailureInfo(
                jobFullName, displayName, groupName,
                failedStage, failedSince,
                failedBuildNumber, buildAbsoluteUrl(job, failedBuildNumber),
                consecutive);
    }

    private String findFailedStage(WorkflowJob job, int buildNumber) {
        if (buildNumber <= 0) return null;

        String cacheKey = job.getFullName() + "#" + buildNumber;
        List<StageRecord> stages = STAGE_CACHE.get(cacheKey);
        if (stages == null) {
            stages = fetchStageRecords(job, buildNumber);
            if (stages != null) {
                STAGE_CACHE.put(cacheKey, stages);
            }
        }
        if (stages == null) return null;

        for (StageRecord s : stages) {
            if ("FAILED".equals(s.status)) return s.name;
        }
        return null;
    }

    /* ===================================================== */
    /*  Stage performance                                    */
    /* ===================================================== */

    private void collectStagePerformance(
            Jenkins jenkins, DashboardEntry entry,
            Map<String, StageDurationAccumulator> accum,
            long now, int historyDays) {

        Item item = jenkins.getItemByFullName(entry.getJobName());
        if (!(item instanceof WorkflowJob)) return;

        WorkflowJob job = (WorkflowJob) item;
        List<BuildRecord> records = fetchBuildRecords(job, now, historyDays);

        int sampled = 0;
        for (BuildRecord r : records) {
            if (r.building) continue;
            if (!"SUCCESS".equals(r.result) && !"UNSTABLE".equals(r.result)) continue;
            if (sampled >= STAGE_SAMPLE_BUILDS) break;

            String cacheKey = job.getFullName() + "#" + r.number;
            List<StageRecord> stages = STAGE_CACHE.get(cacheKey);
            if (stages == null) {
                stages = fetchStageRecords(job, r.number);
                if (stages != null) STAGE_CACHE.put(cacheKey, stages);
            }
            if (stages == null) continue;

            for (StageRecord s : stages) {
                String key = entry.getEffectiveDisplayName() + ":" + s.name;
                accum.computeIfAbsent(key, k ->
                        new StageDurationAccumulator(
                                entry.getJobName(), entry.getEffectiveDisplayName(), s.name));
                accum.get(key).add(s.durationMs);
            }
            sampled++;
        }
    }

    private List<StagePerformance> computeTopSlowStages(
            Map<String, StageDurationAccumulator> accum, int limit) {
        List<StagePerformance> all = new ArrayList<>();
        for (StageDurationAccumulator a : accum.values()) {
            all.add(a.toStagePerformance());
        }
        all.sort((a, b) -> Long.compare(b.getAvgDurationMs(), a.getAvgDurationMs()));
        return all.subList(0, Math.min(limit, all.size()));
    }

    /* ===================================================== */
    /*  Build record fetching & caching                      */
    /* ===================================================== */

    private List<BuildRecord> fetchBuildRecords(
            WorkflowJob job, long now, int historyDays) {

        String cacheKey = job.getFullName();
        CachedBuilds cached = BUILD_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.records;
        }

        List<BuildRecord> records = new ArrayList<>();
        long cutoff = now - (historyDays * DAY_MS);

        for (WorkflowRun run : job.getBuilds()) {
            if (!run.isBuilding() && run.getStartTimeInMillis() < cutoff) break;
            records.add(new BuildRecord(
                    run.getNumber(),
                    run.isBuilding() ? "BUILDING"
                            : (run.getResult() != null ? run.getResult().toString() : "UNKNOWN"),
                    run.getDuration(),
                    run.getStartTimeInMillis(),
                    run.isBuilding()));
            if (records.size() >= MAX_BUILDS_PER_PIPELINE) break;
        }

        BUILD_CACHE.put(cacheKey, new CachedBuilds(records));
        return records;
    }

    private List<StageRecord> fetchStageRecords(WorkflowJob job, int buildNumber) {
        try {
            WorkflowRun run = job.getBuildByNumber(buildNumber);
            if (run == null) return null;

            RunExt runExt = RunExt.create(run);
            List<StageRecord> stages = new ArrayList<>();
            for (StageNodeExt node : runExt.getStages()) {
                stages.add(new StageRecord(
                        node.getName(),
                        node.getStatus() != null ? node.getStatus().name() : "UNKNOWN",
                        node.getDurationMillis()));
            }
            return stages;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to read stages for " + job.getFullName() + " #" + buildNumber, e);
            return null;
        }
    }

    /* ===================================================== */
    /*  Trend computation                                    */
    /* ===================================================== */

    /**
     * Compares the average success rate of the most recent 3 days to
     * the preceding 4 days.  &gt;10 pp improvement → IMPROVING, etc.
     */
    private String computeTrend(double[] dailySuccessRate) {
        if (dailySuccessRate.length < 7) return "STABLE";

        double recent = 0, older = 0;
        int recentCount = 0, olderCount = 0;

        for (int i = dailySuccessRate.length - 3; i < dailySuccessRate.length; i++) {
            recent += dailySuccessRate[i];
            recentCount++;
        }
        for (int i = dailySuccessRate.length - 7; i < dailySuccessRate.length - 3; i++) {
            older += dailySuccessRate[i];
            olderCount++;
        }

        if (recentCount == 0 || olderCount == 0) return "STABLE";
        double diff = (recent / recentCount) - (older / olderCount);
        if (diff > 10) return "IMPROVING";
        if (diff < -10) return "DEGRADING";
        return "STABLE";
    }

    /**
     * Compares average build duration of last 3 completed builds to
     * the 3 before that.  &gt;20% change → trend.
     */
    private String computeDurationTrend(List<BuildRecord> records, long now) {
        List<Long> durations = new ArrayList<>();
        for (BuildRecord r : records) {
            if (!r.building && r.durationMs > 1000) {
                durations.add(r.durationMs);
                if (durations.size() >= 6) break;
            }
        }
        if (durations.size() < 6) return "STABLE";

        double recent = (durations.get(0) + durations.get(1) + durations.get(2)) / 3.0;
        double older = (durations.get(3) + durations.get(4) + durations.get(5)) / 3.0;
        if (older == 0) return "STABLE";

        double change = (recent - older) / older;
        if (change > 0.20) return "DEGRADING";  // builds getting slower
        if (change < -0.20) return "IMPROVING"; // builds getting faster
        return "STABLE";
    }

    /* ===================================================== */
    /*  Helpers                                              */
    /* ===================================================== */

    private static PipelineHealth placeholderHealth(
            String jobName, String displayName, String groupName, String status) {
        return new PipelineHealth(
                jobName, displayName, groupName,
                status, 0, "", 0,
                0, 0, 0, 0, 0,
                "STABLE", "STABLE",
                new int[SPARKLINE_DAYS], new double[SPARKLINE_DAYS],
                false);
    }

    private static String buildAbsoluteUrl(WorkflowJob job, int buildNumber) {
        if (buildNumber <= 0) return "#";
        WorkflowRun run = job.getBuildByNumber(buildNumber);
        if (run == null) return "#";
        String rootUrl = Jenkins.get().getRootUrl();
        String relUrl = run.getUrl();
        if (rootUrl != null && !rootUrl.isEmpty()) {
            if (rootUrl.endsWith("/")) rootUrl = rootUrl.substring(0, rootUrl.length() - 1);
            return rootUrl + "/" + relUrl;
        }
        return "/" + relUrl;
    }

    private static long startOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void evictStaleCache() {
        long cutoff = System.currentTimeMillis() - 300_000;
        BUILD_CACHE.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
        /* stage cache entries for completed builds rarely change, keep longer */
        if (STAGE_CACHE.size() > 500) {
            /* simple eviction: clear half */
            Iterator<String> it = STAGE_CACHE.keySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < STAGE_CACHE.size() / 2) {
                it.next();
                it.remove();
                removed++;
            }
        }
    }

    /* ===================================================== */
    /*  Internal data classes                                 */
    /* ===================================================== */

    private static class BuildRecord {
        final int number;
        final String result;
        final long durationMs;
        final long startTimeMs;
        final boolean building;

        BuildRecord(int number, String result, long durationMs, long startTimeMs, boolean building) {
            this.number = number;
            this.result = result;
            this.durationMs = durationMs;
            this.startTimeMs = startTimeMs;
            this.building = building;
        }
    }

    private static class StageRecord {
        final String name;
        final String status;
        final long durationMs;

        StageRecord(String name, String status, long durationMs) {
            this.name = name;
            this.status = status;
            this.durationMs = durationMs;
        }
    }

    private static class CachedBuilds {
        final List<BuildRecord> records;
        final long timestamp;

        CachedBuilds(List<BuildRecord> records) {
            this.records = records;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > BUILD_CACHE_TTL_MS;
        }
    }

    private static class StageDurationAccumulator {
        final String jobName;
        final String displayName;
        final String stageName;
        long totalMs = 0;
        long maxMs = 0;
        int count = 0;

        StageDurationAccumulator(String jobName, String displayName, String stageName) {
            this.jobName = jobName;
            this.displayName = displayName;
            this.stageName = stageName;
        }

        void add(long durationMs) {
            totalMs += durationMs;
            if (durationMs > maxMs) maxMs = durationMs;
            count++;
        }

        StagePerformance toStagePerformance() {
            long avg = count > 0 ? totalMs / count : 0;
            return new StagePerformance(jobName, displayName, stageName, avg, maxMs, count);
        }
    }
}

package io.jenkins.plugins.pipelineoverview.service;

import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.slaves.Cloud;
import io.jenkins.plugins.pipelineoverview.DashboardEntry;
import io.jenkins.plugins.pipelineoverview.DashboardGroup;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the JSON payload consumed by the v2 dashboard frontend.
 * Computes per-pipeline latest-run topology, currently-broken cards with
 * regression context, leaf-stage outbreak clustering, queue + agents +
 * locks state, and aggregate KPIs.
 */
public class OverviewDataService {

    private static final Logger LOGGER =
            Logger.getLogger(OverviewDataService.class.getName());

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long WEEK_MS = 7 * DAY_MS;
    private static final int MAX_BUILDS_PER_PIPELINE = 200;
    private static final int EXEC_TIMES_LEN = 30;
    private static final int HISTORY_DOTS_LEN = 15;
    private static final int QUEUE_HISTORY_LEN = 60;
    private static final long REGRESSION_WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final long REGRESSION_MIN_GREEN_MS = 6 * 60 * 60 * 1000L;
    private static final long LOCK_WARN_MS = 15 * 60 * 1000L;

    /* ---------- caches ---------- */

    private static final Map<String, CachedBuilds> BUILD_CACHE = new ConcurrentHashMap<>();
    private static final long BUILD_CACHE_TTL_MS = 15_000;

    private static final Map<String, JSONArray> STAGE_TOPO_CACHE = new ConcurrentHashMap<>();

    private static final Deque<Integer> QUEUE_HISTORY = new ArrayDeque<>(QUEUE_HISTORY_LEN);

    /* ===================================================== */

    public JSONObject fetchDashboardData(List<DashboardGroup> groups, int historyDays) {
        long now = System.currentTimeMillis();
        Jenkins jenkins = Jenkins.get();

        // 1. Collect per-pipeline data
        Map<String, PipelineSnapshot> snaps = new LinkedHashMap<>();
        Map<String, String> jobToGroup = new LinkedHashMap<>();
        for (DashboardGroup group : groups) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (!entry.isEnabled()) continue;
                jobToGroup.put(entry.getJobName(), group.getName());
                try {
                    PipelineSnapshot snap = collectPipelineSnapshot(jenkins, entry, group.getName(), now, historyDays);
                    if (snap != null) snaps.put(entry.getJobName(), snap);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to collect snapshot for " + entry.getJobName(), e);
                }
            }
        }

        // 2. Aggregate week stats + categorize
        long sevenDaysAgo = now - WEEK_MS;
        long fourteenDaysAgo = now - 2 * WEEK_MS;
        int totalThisWeek = 0, successThisWeek = 0;
        int totalLastWeek = 0, successLastWeek = 0;

        List<PipelineSnapshot> broken = new ArrayList<>();
        List<PipelineSnapshot> unstableList = new ArrayList<>();
        int buildingCount = 0;

        for (PipelineSnapshot p : snaps.values()) {
            for (BuildRecord r : p.records) {
                if (r.building) continue;
                if (r.startTimeMs >= sevenDaysAgo) {
                    totalThisWeek++;
                    if ("SUCCESS".equals(r.result)) successThisWeek++;
                } else if (r.startTimeMs >= fourteenDaysAgo) {
                    totalLastWeek++;
                    if ("SUCCESS".equals(r.result)) successLastWeek++;
                }
            }
            if ("FAILURE".equals(p.lastResult)) broken.add(p);
            if ("UNSTABLE".equals(p.lastResult)) unstableList.add(p);
            if (p.building) buildingCount++;
        }

        double weekRate = totalThisWeek > 0 ? (successThisWeek * 100.0 / totalThisWeek) : 100.0;
        double weekRateLast = totalLastWeek > 0 ? (successLastWeek * 100.0 / totalLastWeek) : 100.0;

        // 3. Detect regressions: broken pipelines that were green for at least REGRESSION_MIN_GREEN_MS
        //    and broke within REGRESSION_WINDOW_MS
        List<JSONObject> regressions = new ArrayList<>();
        for (PipelineSnapshot p : broken) {
            if (p.brokeAtMs == 0 || p.lastGreenTimeMs == 0) continue;
            long greenDuration = p.brokeAtMs - p.lastGreenTimeMs;
            long brokeAge = now - p.brokeAtMs;
            if (greenDuration >= REGRESSION_MIN_GREEN_MS && brokeAge <= REGRESSION_WINDOW_MS) {
                JSONObject r = new JSONObject();
                r.put("jobName", p.jobName);
                r.put("displayName", p.displayName);
                r.put("greenDurationMs", greenDuration);
                r.put("brokeAtMs", p.brokeAtMs);
                r.put("failedStage", p.failedStageName != null ? p.failedStageName : "");
                r.put("buildNumber", p.lastBuildNumber);
                r.put("buildUrl", p.lastBuildUrl);
                regressions.add(r);
            }
        }
        regressions.sort((a, b) -> Long.compare(b.getLong("brokeAtMs"), a.getLong("brokeAtMs")));

        // 4. Outbreaks: cluster broken by leaf failed stage (≥2 pipelines)
        Map<String, List<String>> stageToNames = new LinkedHashMap<>();
        for (PipelineSnapshot p : broken) {
            String stage = p.failedStageName;
            if (stage == null || stage.isEmpty()) continue;
            stageToNames.computeIfAbsent(stage, k -> new ArrayList<>()).add(p.displayName);
        }
        List<JSONObject> outbreaksList = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : stageToNames.entrySet()) {
            if (e.getValue().size() < 2) continue;
            JSONObject c = new JSONObject();
            c.put("stageName", e.getKey());
            c.put("count", e.getValue().size());
            JSONArray pl = new JSONArray();
            pl.addAll(e.getValue());
            c.put("pipelines", pl);
            outbreaksList.add(c);
        }
        outbreaksList.sort((a, b) -> b.getInt("count") - a.getInt("count"));

        // 5. Queue
        Queue.Item[] queueItems = Queue.getInstance().getItems();
        long avgWaitMs = 0;
        if (queueItems.length > 0) {
            long sum = 0;
            for (Queue.Item qi : queueItems) sum += now - qi.getInQueueSince();
            avgWaitMs = sum / queueItems.length;
        }
        sampleQueueHistory(queueItems.length);

        // 6. Agents
        JSONObject agentsJson = listAgents(jenkins);
        int agentsHealthy = countHealthyAgents(agentsJson);
        int agentsTotal = countTotalAgents(agentsJson);

        // 7. Build response
        JSONObject result = new JSONObject();
        result.put("timestamp", now);

        JSONObject summary = new JSONObject();
        summary.put("weekSuccessRate", round1(weekRate));
        summary.put("weekSuccessRateLastWeek", round1(weekRateLast));
        summary.put("downCount", broken.size());
        summary.put("unstableCount", unstableList.size());
        summary.put("buildingCount", buildingCount);
        summary.put("outbreakCount", outbreaksList.size());
        summary.put("queueSize", queueItems.length);
        summary.put("avgQueueWaitMs", avgWaitMs);
        summary.put("agentsHealthy", agentsHealthy);
        summary.put("agentsTotal", agentsTotal);
        result.put("summary", summary);

        JSONArray outbreaksArr = new JSONArray();
        outbreaksArr.addAll(outbreaksList);
        result.put("outbreaks", outbreaksArr);

        JSONArray regArr = new JSONArray();
        regArr.addAll(regressions);
        result.put("regressions", regArr);

        // Broken cards (sorted by sinceGreen desc)
        List<PipelineSnapshot> brokenSorted = new ArrayList<>(broken);
        brokenSorted.sort(Comparator.comparingLong((PipelineSnapshot p) -> {
            long sinceGreen = (p.brokeAtMs > 0) ? (now - p.lastGreenTimeMs) : 0;
            return -sinceGreen;
        }));
        JSONArray brokenArr = new JSONArray();
        for (PipelineSnapshot p : brokenSorted) brokenArr.add(brokenToJSON(p, now));
        result.put("broken", brokenArr);

        // Groups → pipelines (skip pipelines with no recent runs)
        int hiddenInactive = 0;
        JSONArray groupsArr = new JSONArray();
        for (DashboardGroup group : groups) {
            JSONObject g = new JSONObject();
            g.put("name", group.getName());
            JSONArray ps = new JSONArray();
            for (DashboardEntry entry : group.getPipelines()) {
                if (!entry.isEnabled()) continue;
                PipelineSnapshot p = snaps.get(entry.getJobName());
                if (p == null) continue;
                if (p.records.isEmpty()) { hiddenInactive++; continue; }
                ps.add(pipelineToJSON(p));
            }
            g.put("pipelines", ps);
            groupsArr.add(g);
        }
        result.put("groups", groupsArr);
        result.put("hiddenInactive", hiddenInactive);

        // Queue history sparkline
        JSONArray qh = new JSONArray();
        synchronized (QUEUE_HISTORY) {
            for (Integer v : QUEUE_HISTORY) qh.add(v);
        }
        result.put("queueHistory", qh);

        // Locks
        result.put("locks", listLocks(now));

        // Unstable list (sort by count desc)
        unstableList.sort((a, b) -> b.unstableCount7d - a.unstableCount7d);
        JSONArray unArr = new JSONArray();
        for (PipelineSnapshot p : unstableList) {
            if (p.totalBuilds7d == 0) continue;
            JSONObject u = new JSONObject();
            u.put("jobName", p.jobName);
            u.put("displayName", p.displayName);
            u.put("unstableCount", p.unstableCount7d);
            u.put("totalCount", p.totalBuilds7d);
            unArr.add(u);
        }
        result.put("unstable", unArr);

        result.put("agents", agentsJson);

        evictStaleCache();
        return result;
    }

    /* ===================================================== */
    /*  Per-pipeline collection                              */
    /* ===================================================== */

    private PipelineSnapshot collectPipelineSnapshot(
            Jenkins jenkins, DashboardEntry entry, String groupName,
            long now, int historyDays) {

        Item item = jenkins.getItemByFullName(entry.getJobName());
        if (!(item instanceof WorkflowJob)) return null;
        if (!item.hasPermission(Item.READ)) return null;

        WorkflowJob job = (WorkflowJob) item;
        List<BuildRecord> records = fetchBuildRecords(job, now, historyDays);

        PipelineSnapshot snap = new PipelineSnapshot();
        snap.jobName = entry.getJobName();
        snap.displayName = entry.getEffectiveDisplayName();
        snap.groupName = groupName;
        snap.records = records;

        if (records.isEmpty()) return snap;

        BuildRecord latest = records.get(0);
        snap.lastBuildNumber = latest.number;
        snap.lastBuildUrl = buildAbsoluteUrl(job, latest.number);
        snap.lastBuildTimeMs = latest.startTimeMs;
        snap.building = latest.building;
        snap.lastResult = latest.building ? "BUILDING" : (latest.result != null ? latest.result : "UNKNOWN");

        // Latest run topology (cached per build #)
        snap.stagesTopology = getStageTopology(job, latest.number);

        // 7d totals + unstable count
        long sevenAgo = now - WEEK_MS;
        for (BuildRecord r : records) {
            if (r.building) continue;
            if (r.startTimeMs >= sevenAgo) {
                snap.totalBuilds7d++;
                if ("UNSTABLE".equals(r.result)) snap.unstableCount7d++;
            }
        }

        // Successful build durations (oldest → newest, last EXEC_TIMES_LEN)
        snap.successDurationsSec = extractSuccessDurations(records);

        // History dots (last HISTORY_DOTS_LEN, oldest → newest)
        snap.historyDots = extractHistoryDots(records);

        // If currently failing: find regression context
        if ("FAILURE".equals(snap.lastResult)) {
            int consecutive = 0;
            long brokeAt = latest.startTimeMs;
            int lastGreenBuild = 0;
            long lastGreenTime = 0;
            for (BuildRecord r : records) {
                if (r.building) continue;
                if ("FAILURE".equals(r.result)) {
                    consecutive++;
                    brokeAt = r.startTimeMs;
                } else if ("SUCCESS".equals(r.result)) {
                    lastGreenBuild = r.number;
                    lastGreenTime = r.startTimeMs;
                    break;
                }
            }
            snap.consecutiveFailures = consecutive;
            snap.brokeAtMs = brokeAt;
            snap.lastGreenBuildNumber = lastGreenBuild;
            snap.lastGreenTimeMs = lastGreenTime;
            snap.failedStage = findFailedStage(snap.stagesTopology);
            if (snap.failedStage != null) {
                snap.failedStageName = snap.failedStage.optString("name", null);
            }
        }

        return snap;
    }

    /* ===================================================== */
    /*  Stage topology — flat list with parallel detection   */
    /* ===================================================== */

    private JSONArray getStageTopology(WorkflowJob job, int buildNumber) {
        if (buildNumber <= 0) return new JSONArray();
        String key = job.getFullName() + "#" + buildNumber;
        JSONArray cached = STAGE_TOPO_CACHE.get(key);
        if (cached != null) return cached;

        JSONArray topology = new JSONArray();
        try {
            WorkflowRun run = job.getBuildByNumber(buildNumber);
            if (run == null) return topology;
            FlowExecution exec = run.getExecution();

            RunExt runExt = RunExt.create(run);
            List<StageNodeExt> stages = runExt.getStages();
            if (stages == null || stages.isEmpty()) return topology;

            // Group parallel branches by their enclosing parallel block id.
            // A "parallel branch" is a FlowNode with a ThreadNameAction; its first
            // enclosing BlockStartNode is the parallel block start.
            Map<String, JSONObject> parallelByParent = new LinkedHashMap<>();
            for (StageNodeExt s : stages) {
                String stageName = s.getName();
                String status = mapStageStatus(s.getStatus());

                FlowNode node = null;
                if (exec != null) {
                    try { node = exec.getNode(s.getId()); } catch (Exception ignored) {}
                }

                String parallelParentId = null;
                if (node != null && node.getAction(ThreadNameAction.class) != null) {
                    List<BlockStartNode> enclosing = node.getEnclosingBlocks();
                    if (enclosing != null && !enclosing.isEmpty()) {
                        parallelParentId = enclosing.get(0).getId();
                    } else {
                        parallelParentId = "anonymous-parallel";
                    }
                }

                if (parallelParentId != null) {
                    JSONObject parallelObj = parallelByParent.get(parallelParentId);
                    if (parallelObj == null) {
                        parallelObj = new JSONObject();
                        parallelObj.put("type", "parallel");
                        parallelObj.put("children", new JSONArray());
                        topology.add(parallelObj);
                        parallelByParent.put(parallelParentId, parallelObj);
                    }
                    JSONObject branch = new JSONObject();
                    branch.put("name", stageName);
                    branch.put("status", status);
                    parallelObj.getJSONArray("children").add(branch);
                } else {
                    JSONObject cell = new JSONObject();
                    cell.put("type", "seq");
                    cell.put("name", stageName);
                    cell.put("status", status);
                    topology.add(cell);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to build stage topology for " + job.getFullName() + " #" + buildNumber, e);
        }

        STAGE_TOPO_CACHE.put(key, topology);
        return topology;
    }

    private String mapStageStatus(StatusExt status) {
        if (status == null) return "skipped";
        switch (status) {
            case SUCCESS: return "ok";
            case FAILED:  return "fail";
            case UNSTABLE: return "unstable";
            case IN_PROGRESS: return "building";
            case PAUSED_PENDING_INPUT: return "building";
            case ABORTED: return "skipped";
            case NOT_EXECUTED: return "skipped";
            default: return "skipped";
        }
    }

    private JSONObject findFailedStage(JSONArray topology) {
        for (int i = 0; i < topology.size(); i++) {
            JSONObject node = topology.getJSONObject(i);
            if ("parallel".equals(node.optString("type"))) {
                JSONArray children = node.optJSONArray("children");
                if (children == null || children.isEmpty()) continue;
                List<JSONObject> failed = new ArrayList<>();
                for (int j = 0; j < children.size(); j++) {
                    JSONObject c = children.getJSONObject(j);
                    if ("fail".equals(c.optString("status"))) failed.add(c);
                }
                if (!failed.isEmpty()) {
                    // Use the parallel block's first child name as a representative,
                    // OR keep the parent name if available. We don't have parent name
                    // in the topology, so use the first failed branch's name as the
                    // stage label to cluster on. Better: take the longest common prefix.
                    JSONObject result = new JSONObject();
                    String repr = pickParallelLabel(children);
                    result.put("name", repr);
                    result.put("isParallel", true);
                    JSONArray branches = new JSONArray();
                    for (int j = 0; j < children.size(); j++) {
                        JSONObject c = children.getJSONObject(j);
                        JSONObject b = new JSONObject();
                        b.put("name", c.getString("name"));
                        b.put("status", "fail".equals(c.optString("status")) ? "FAILED" : c.optString("status"));
                        branches.add(b);
                    }
                    result.put("branches", branches);
                    return result;
                }
            } else {
                if ("fail".equals(node.optString("status"))) {
                    JSONObject result = new JSONObject();
                    result.put("name", node.getString("name"));
                    result.put("isParallel", false);
                    return result;
                }
            }
        }
        return null;
    }

    /** Pick a representative label for a parallel block. Uses the longest common prefix
     *  (Jenkins-style "Block / Branch" naming) or falls back to the first child's name. */
    private String pickParallelLabel(JSONArray children) {
        if (children == null || children.isEmpty()) return "";
        String first = children.getJSONObject(0).getString("name");
        // If all children have the form "X / Y" with the same X, return X.
        String prefix = first.contains(" / ") ? first.substring(0, first.indexOf(" / ")) : null;
        if (prefix != null) {
            for (int i = 1; i < children.size(); i++) {
                String n = children.getJSONObject(i).getString("name");
                if (!n.startsWith(prefix + " / ")) { prefix = null; break; }
            }
            if (prefix != null) return prefix;
        }
        return first;
    }

    /* ===================================================== */
    /*  Build record fetching                                */
    /* ===================================================== */

    private List<BuildRecord> fetchBuildRecords(WorkflowJob job, long now, int historyDays) {
        String cacheKey = job.getFullName();
        CachedBuilds cached = BUILD_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.records;

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

    /* ===================================================== */
    /*  Per-pipeline derived fields                          */
    /* ===================================================== */

    private JSONArray extractSuccessDurations(List<BuildRecord> records) {
        // Records are newest-first. Take last N successful runs, oldest→newest.
        List<Integer> seconds = new ArrayList<>();
        for (BuildRecord r : records) {
            if (r.building) continue;
            if (!"SUCCESS".equals(r.result)) continue;
            if (r.durationMs <= 1000) continue;
            seconds.add((int) (r.durationMs / 1000));
            if (seconds.size() >= EXEC_TIMES_LEN) break;
        }
        Collections.reverse(seconds);
        JSONArray arr = new JSONArray();
        arr.addAll(seconds);
        return arr;
    }

    private JSONArray extractHistoryDots(List<BuildRecord> records) {
        // Last HISTORY_DOTS_LEN runs, oldest→newest. Map status → ok/fail/unstable/building.
        List<String> dots = new ArrayList<>();
        for (BuildRecord r : records) {
            if (dots.size() >= HISTORY_DOTS_LEN) break;
            String s;
            if (r.building) s = "building";
            else if ("SUCCESS".equals(r.result)) s = "ok";
            else if ("FAILURE".equals(r.result)) s = "fail";
            else if ("UNSTABLE".equals(r.result)) s = "unstable";
            else s = "skipped";
            dots.add(s);
        }
        Collections.reverse(dots);
        JSONArray arr = new JSONArray();
        arr.addAll(dots);
        return arr;
    }

    /* ===================================================== */
    /*  JSON shaping for groups + broken                     */
    /* ===================================================== */

    private JSONObject pipelineToJSON(PipelineSnapshot p) {
        JSONObject o = new JSONObject();
        o.put("jobName", p.jobName);
        o.put("displayName", p.displayName);
        o.put("buildNumber", p.lastBuildNumber);
        o.put("buildUrl", p.lastBuildUrl);
        o.put("stages", p.stagesTopology != null ? p.stagesTopology : new JSONArray());
        o.put("execTimes", p.successDurationsSec != null ? p.successDurationsSec : new JSONArray());
        return o;
    }

    private JSONObject brokenToJSON(PipelineSnapshot p, long now) {
        JSONObject o = new JSONObject();
        o.put("jobName", p.jobName);
        o.put("displayName", p.displayName);
        o.put("groupName", p.groupName);
        long sinceGreen = (p.lastGreenTimeMs > 0) ? (now - p.lastGreenTimeMs) : (now - p.brokeAtMs);
        o.put("sinceGreenMs", sinceGreen);
        o.put("consecutiveFailures", p.consecutiveFailures);
        o.put("lastGreenBuildNumber", p.lastGreenBuildNumber);
        o.put("buildNumber", p.lastBuildNumber);
        o.put("buildUrl", p.lastBuildUrl);
        if (p.failedStage != null) o.put("failedStage", p.failedStage);
        o.put("history", p.historyDots != null ? p.historyDots : new JSONArray());
        return o;
    }

    /* ===================================================== */
    /*  Queue history sampler                                */
    /* ===================================================== */

    private void sampleQueueHistory(int currentDepth) {
        synchronized (QUEUE_HISTORY) {
            QUEUE_HISTORY.addLast(currentDepth);
            while (QUEUE_HISTORY.size() > QUEUE_HISTORY_LEN) QUEUE_HISTORY.pollFirst();
        }
    }

    /* ===================================================== */
    /*  Agents                                                */
    /* ===================================================== */

    private JSONObject listAgents(Jenkins jenkins) {
        JSONObject result = new JSONObject();
        JSONArray permanent = new JSONArray();
        JSONArray clouds = new JSONArray();

        // Permanent agents (non-cloud, non-master)
        for (Computer c : jenkins.getComputers()) {
            String name = c.getName();
            if (name == null || name.isEmpty()) continue; // built-in/master
            // Skip cloud-provisioned slaves
            try {
                if (c instanceof hudson.slaves.AbstractCloudComputer) continue;
            } catch (NoClassDefFoundError ignored) {}

            JSONObject ag = new JSONObject();
            ag.put("name", name);
            String status;
            if (c.isOffline()) status = "down";
            else if (c.countBusy() >= c.getNumExecutors()) status = "partial";
            else status = "ok";
            ag.put("status", status);
            ag.put("executorsBusy", c.countBusy());
            ag.put("executorsTotal", c.getNumExecutors());
            permanent.add(ag);
        }

        // Clouds
        for (Cloud cloud : jenkins.clouds) {
            JSONObject cl = new JSONObject();
            cl.put("name", cloud.name);
            int hot = 0;
            int max = 0;
            try {
                java.lang.reflect.Method m = cloud.getClass().getMethod("getMaxSize");
                Object v = m.invoke(cloud);
                if (v instanceof Number) max = ((Number) v).intValue();
            } catch (Exception ignored) {}
            // Count online cloud agents whose computer name matches the cloud name
            for (Computer c : jenkins.getComputers()) {
                if (c.getName() == null || c.getName().isEmpty()) continue;
                try {
                    if (!(c instanceof hudson.slaves.AbstractCloudComputer)) continue;
                } catch (NoClassDefFoundError e) { continue; }
                if (!c.isOnline()) continue;
                if (c.getName().contains(cloud.name) || c.getName().startsWith(cloud.name)) hot++;
            }
            cl.put("hot", hot);
            cl.put("max", max);
            clouds.add(cl);
        }

        result.put("permanent", permanent);
        result.put("clouds", clouds);
        return result;
    }

    private int countHealthyAgents(JSONObject agents) {
        int healthy = 0;
        JSONArray perm = agents.optJSONArray("permanent");
        if (perm != null) {
            for (int i = 0; i < perm.size(); i++) {
                JSONObject a = perm.getJSONObject(i);
                if ("ok".equals(a.optString("status")) || "partial".equals(a.optString("status"))) healthy++;
            }
        }
        return healthy;
    }

    private int countTotalAgents(JSONObject agents) {
        JSONArray perm = agents.optJSONArray("permanent");
        return perm != null ? perm.size() : 0;
    }

    /* ===================================================== */
    /*  Lockable resources (via reflection — optional dep)   */
    /* ===================================================== */

    private JSONArray listLocks(long now) {
        JSONArray arr = new JSONArray();
        try {
            Class<?> mgrCls = Class.forName("org.jenkins.plugins.lockableresources.LockableResourcesManager");
            Object mgr = mgrCls.getMethod("get").invoke(null);
            Object resourcesObj = mgrCls.getMethod("getResources").invoke(mgr);
            if (!(resourcesObj instanceof List)) return arr;
            List<?> resources = (List<?>) resourcesObj;

            for (Object r : resources) {
                JSONObject l = new JSONObject();
                String name = (String) r.getClass().getMethod("getName").invoke(r);
                boolean locked;
                try {
                    locked = (Boolean) r.getClass().getMethod("isLocked").invoke(r);
                } catch (NoSuchMethodException nsme) {
                    Object holder = r.getClass().getMethod("getBuild").invoke(r);
                    locked = (holder != null);
                }
                l.put("name", name);
                if (locked) {
                    long holdMs = 0;
                    try {
                        Object build = r.getClass().getMethod("getBuild").invoke(r);
                        if (build != null) {
                            long startTime = (Long) build.getClass().getMethod("getStartTimeInMillis").invoke(build);
                            holdMs = now - startTime;
                        }
                    } catch (Exception ignored) {}
                    l.put("status", "held");
                    l.put("holdMs", holdMs);
                    l.put("stale", holdMs > LOCK_WARN_MS);
                } else {
                    l.put("status", "free");
                    l.put("holdMs", 0L);
                    l.put("stale", false);
                }
                arr.add(l);
            }
        } catch (ClassNotFoundException e) {
            // lockable-resources plugin not installed — return empty
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read lockable resources via reflection", e);
        }
        return arr;
    }

    /* ===================================================== */
    /*  Helpers                                              */
    /* ===================================================== */

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

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void evictStaleCache() {
        long cutoff = System.currentTimeMillis() - 300_000;
        BUILD_CACHE.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
        if (STAGE_TOPO_CACHE.size() > 500) {
            int target = STAGE_TOPO_CACHE.size() / 2;
            int removed = 0;
            for (String k : new ArrayList<>(STAGE_TOPO_CACHE.keySet())) {
                if (removed >= target) break;
                STAGE_TOPO_CACHE.remove(k);
                removed++;
            }
        }
    }

    /* ===================================================== */
    /*  Internal data classes                                */
    /* ===================================================== */

    private static class PipelineSnapshot {
        String jobName;
        String displayName;
        String groupName;
        List<BuildRecord> records = Collections.emptyList();
        int lastBuildNumber;
        String lastBuildUrl = "#";
        long lastBuildTimeMs;
        String lastResult;
        boolean building;
        JSONArray stagesTopology;
        JSONArray successDurationsSec;
        JSONArray historyDots;
        int totalBuilds7d;
        int unstableCount7d;
        // Failure / regression context
        int consecutiveFailures;
        long brokeAtMs;
        int lastGreenBuildNumber;
        long lastGreenTimeMs;
        JSONObject failedStage;
        String failedStageName;
    }

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
}

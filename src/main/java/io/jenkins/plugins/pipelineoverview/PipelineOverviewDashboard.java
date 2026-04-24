package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelineoverview.service.OverviewDataService;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Jenkins {@link View} that renders a TV-optimised overview dashboard
 * showing pipeline health, build velocity, failure tracking, and trend
 * data across all configured pipelines.
 */
public class PipelineOverviewDashboard extends View {

    private static final Logger LOGGER =
            Logger.getLogger(PipelineOverviewDashboard.class.getName());

    private List<DashboardGroup> groups;
    private int refreshIntervalSeconds;
    private int historyDays;
    private String headerMessage;
    private String dashboardTitle;

    @DataBoundConstructor
    public PipelineOverviewDashboard(String name) {
        super(name);
        initDefaults();
    }

    public PipelineOverviewDashboard(String name, ViewGroup owner) {
        super(name, owner);
        initDefaults();
    }

    private void initDefaults() {
        this.groups = new ArrayList<>();
        this.refreshIntervalSeconds = 30;
        this.historyDays = 30;
        this.headerMessage = "";
        this.dashboardTitle = "";
    }

    /* ===== getters ===== */

    public List<DashboardGroup> getGroups() {
        return groups != null ? groups : new ArrayList<>();
    }
    public int getRefreshIntervalSeconds() { return refreshIntervalSeconds; }
    public int getHistoryDays()            { return historyDays; }
    public String getHeaderMessage() {
        return headerMessage != null ? headerMessage : "";
    }
    public String getDashboardTitle() {
        return (dashboardTitle != null && !dashboardTitle.isEmpty())
                ? dashboardTitle : getViewName();
    }

    /* ===== setters ===== */

    @DataBoundSetter
    public void setGroups(List<DashboardGroup> groups) {
        this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
    }

    @DataBoundSetter
    public void setRefreshIntervalSeconds(int v) {
        this.refreshIntervalSeconds = Math.max(5, v);
    }

    @DataBoundSetter
    public void setHistoryDays(int v) {
        this.historyDays = Math.max(1, Math.min(90, v));
    }

    @DataBoundSetter
    public void setHeaderMessage(String v) {
        this.headerMessage = v;
    }

    @DataBoundSetter
    public void setDashboardTitle(String v) {
        this.dashboardTitle = v;
    }

    /* ===== View contract ===== */

    @Override
    public Collection<TopLevelItem> getItems() {
        Set<TopLevelItem> items = new LinkedHashSet<>();
        Jenkins jenkins = Jenkins.get();
        for (DashboardGroup group : getGroups()) {
            for (DashboardEntry entry : group.getPipelines()) {
                Item item = jenkins.getItemByFullName(entry.getJobName());
                if (item instanceof TopLevelItem && item.hasPermission(Item.READ)) {
                    items.add((TopLevelItem) item);
                }
            }
        }
        return items;
    }

    @Override
    public boolean contains(TopLevelItem item) {
        for (DashboardGroup group : getGroups()) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (entry.getJobName().equals(item.getFullName())) return true;
            }
        }
        return false;
    }

    @Override
    protected void submit(StaplerRequest2 req)
            throws IOException, ServletException, Descriptor.FormException {
        JSONObject json = req.getSubmittedForm();
        this.refreshIntervalSeconds = Math.max(5, json.optInt("refreshIntervalSeconds", 30));
        this.historyDays = Math.max(1, Math.min(90, json.optInt("historyDays", 30)));
        this.headerMessage = json.optString("headerMessage", "");
        this.dashboardTitle = json.optString("dashboardTitle", "");

        Object groupsData = json.opt("groups");
        if (groupsData != null) {
            this.groups = req.bindJSONToList(DashboardGroup.class, groupsData);
        } else {
            this.groups = new ArrayList<>();
        }
    }

    @Override
    public Item doCreateItem(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        return Jenkins.get().doCreateItem(req, rsp);
    }

    @Override
    public void onJobRenamed(Item item, String oldName, String newName) {
        if (groups == null) return;
        boolean changed = false;
        for (DashboardGroup group : groups) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (entry.getJobName().equals(oldName)) {
                    entry.setJobName(newName);
                    changed = true;
                }
            }
        }
        if (changed) {
            try { save(); }
            catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to save view after job rename", e);
            }
        }
    }

    /* ===== REST endpoint ===== */

    public void doData(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        try {
            OverviewDataService service = new OverviewDataService();
            JSONObject result = service.fetchDashboardData(getGroups(), historyDays);

            result.put("viewName", getDashboardTitle());
            result.put("headerMessage", getHeaderMessage());

            rsp.getWriter().write(result.toString());
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to build dashboard data", t);
            JSONObject error = new JSONObject();
            String msg = t.getMessage();
            error.put("error", (msg != null ? msg : t.getClass().getSimpleName()));
            error.put("timestamp", System.currentTimeMillis());
            rsp.getWriter().write(error.toString());
        }
    }

    /* ===== Descriptor ===== */

    @Extension
    @Symbol("pipelineOverviewDashboard")
    public static class DescriptorImpl extends ViewDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.PipelineOverviewDashboard_DisplayName();
        }

        @POST
        public FormValidation doCheckRefreshIntervalSeconds(@QueryParameter int value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value < 5) return FormValidation.error("Minimum is 5 seconds.");
            if (value > 3600) return FormValidation.warning("Consider a value under 300 seconds.");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckHistoryDays(@QueryParameter int value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value < 1 || value > 90) return FormValidation.error("Must be between 1 and 90.");
            return FormValidation.ok();
        }
    }
}

package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.View;
import io.jenkins.plugins.pipelineoverview.service.OverviewDataService;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sidebar entry that opens the dashboard full-screen, reading config from the View. */
@Extension
public class PipelineOverviewLink implements RootAction {

    private static final Logger LOGGER =
            Logger.getLogger(PipelineOverviewLink.class.getName());

    @Override
    public String getIconFileName() {
        return "symbol-tv-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Pipeline Overview";
    }

    @Override
    public String getUrlName() {
        return "pipeline-overview";
    }

    public PipelineOverviewDashboard getView() {
        return findFirstDashboardView();
    }

    @POST
    public void doData(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        PipelineOverviewDashboard view = findFirstDashboardView();
        if (view == null) {
            JSONObject error = new JSONObject();
            error.put("error", "No Pipeline Overview view configured. Define one via JCasC under views.");
            rsp.getWriter().write(error.toString());
            return;
        }

        try {
            OverviewDataService service = new OverviewDataService();
            JSONObject result = service.fetchDashboardData(view.getGroups(), view.getHistoryDays());
            result.put("viewName", view.getDashboardTitle());
            result.put("headerMessage", view.getHeaderMessage());
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

    private PipelineOverviewDashboard findFirstDashboardView() {
        Jenkins j = Jenkins.get();
        for (View v : j.getViews()) {
            if (v instanceof PipelineOverviewDashboard) return (PipelineOverviewDashboard) v;
        }
        return null;
    }
}

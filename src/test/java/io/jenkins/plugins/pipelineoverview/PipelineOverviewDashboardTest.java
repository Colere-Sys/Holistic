package io.jenkins.plugins.pipelineoverview;

import hudson.model.ViewDescriptor;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link PipelineOverviewDashboard}.
 */
public class PipelineOverviewDashboardTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void viewDescriptorIsRegistered() {
        ViewDescriptor descriptor = null;
        for (ViewDescriptor d : j.jenkins.getExtensionList(ViewDescriptor.class)) {
            if (d.clazz == PipelineOverviewDashboard.class) {
                descriptor = d;
                break;
            }
        }
        assertNotNull("PipelineOverviewDashboard descriptor should be registered", descriptor);
        assertEquals("Pipeline Overview Dashboard", descriptor.getDisplayName());
    }

    @Test
    public void defaultConfigurationValues() {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        assertEquals(30, view.getRefreshIntervalSeconds());
        assertEquals(30, view.getHistoryDays());
        assertEquals("", view.getHeaderMessage());
        assertTrue(view.getGroups().isEmpty());
    }

    @Test
    public void refreshIntervalClampedToMinimum() {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        view.setRefreshIntervalSeconds(1);
        assertEquals(5, view.getRefreshIntervalSeconds());
    }

    @Test
    public void historyDaysClampedToRange() {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        view.setHistoryDays(0);
        assertEquals(1, view.getHistoryDays());
        view.setHistoryDays(200);
        assertEquals(90, view.getHistoryDays());
    }

    @Test
    public void containsReturnsFalseWhenEmpty() throws Exception {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        j.jenkins.addView(view);

        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                j.jenkins.createProject(
                        org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "my-pipeline");

        assertFalse(view.contains(job));
    }

    @Test
    public void containsReturnsTrueForConfiguredJob() throws Exception {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        j.jenkins.addView(view);

        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                j.jenkins.createProject(
                        org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "my-pipeline");

        DashboardEntry entry = new DashboardEntry("my-pipeline");
        DashboardGroup group = new DashboardGroup("Test Group");
        group.setPipelines(Collections.singletonList(entry));
        view.setGroups(Collections.singletonList(group));

        assertTrue(view.contains(job));
    }

    @Test
    public void multipleGroupsOrdering() {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");

        DashboardGroup g1 = new DashboardGroup("Alpha");
        DashboardGroup g2 = new DashboardGroup("Beta");
        view.setGroups(Arrays.asList(g1, g2));

        List<DashboardGroup> groups = view.getGroups();
        assertEquals(2, groups.size());
        assertEquals("Alpha", groups.get(0).getName());
        assertEquals("Beta", groups.get(1).getName());
    }

    @Test
    public void dashboardTitleFallsBackToViewName() {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("my-view");
        assertEquals("my-view", view.getDashboardTitle());
        view.setDashboardTitle("Custom Title");
        assertEquals("Custom Title", view.getDashboardTitle());
    }

    @Test
    public void onJobRenamedUpdatesEntries() throws Exception {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("test-view");
        j.jenkins.addView(view);

        org.jenkinsci.plugins.workflow.job.WorkflowJob job =
                j.jenkins.createProject(
                        org.jenkinsci.plugins.workflow.job.WorkflowJob.class, "old-name");

        DashboardEntry entry = new DashboardEntry("old-name");
        DashboardGroup group = new DashboardGroup("Group");
        group.setPipelines(Collections.singletonList(entry));
        view.setGroups(Collections.singletonList(group));

        view.onJobRenamed(job, "old-name", "new-name");
        assertEquals("new-name", view.getGroups().get(0).getPipelines().get(0).getJobName());
    }
}

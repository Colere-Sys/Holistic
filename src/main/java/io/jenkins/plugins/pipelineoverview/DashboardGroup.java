package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named group of {@link DashboardEntry} items for the overview dashboard.
 */
public class DashboardGroup extends AbstractDescribableImpl<DashboardGroup> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private List<DashboardEntry> pipelines;

    @DataBoundConstructor
    public DashboardGroup(String name) {
        this.name = (name != null) ? name.trim() : "Ungrouped";
        this.pipelines = new ArrayList<>();
    }

    public String getName() { return name; }

    public List<DashboardEntry> getPipelines() {
        return pipelines != null ? pipelines : Collections.emptyList();
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = (name != null) ? name.trim() : "Ungrouped";
    }

    @DataBoundSetter
    public void setPipelines(List<DashboardEntry> pipelines) {
        this.pipelines = (pipelines != null) ? new ArrayList<>(pipelines) : new ArrayList<>();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DashboardGroup> {

        @Override
        public String getDisplayName() {
            return Messages.DashboardGroup_DisplayName();
        }

        @POST
        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(Messages.DashboardGroup_NameRequired());
            }
            return FormValidation.ok();
        }
    }
}

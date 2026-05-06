package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.View;
import hudson.model.ViewGroup;
import hudson.model.listeners.ItemListener;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class JobListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(JobListener.class.getName());

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        forEachDashboard(view -> {
            if (view.renameJob(oldFullName, newFullName)) {
                persist(view);
            }
        });
    }

    @Override
    public void onDeleted(Item item) {
        String fullName = item.getFullName();
        forEachDashboard(view -> {
            if (view.removeJob(fullName)) {
                persist(view);
            }
        });
    }

    private void forEachDashboard(java.util.function.Consumer<PipelineOverviewDashboard> action) {
        visit(Jenkins.get(), action);
    }

    private void visit(ViewGroup group, java.util.function.Consumer<PipelineOverviewDashboard> action) {
        for (View v : group.getViews()) {
            if (v instanceof PipelineOverviewDashboard) {
                action.accept((PipelineOverviewDashboard) v);
            }
            if (v instanceof ViewGroup) {
                visit((ViewGroup) v, action);
            }
        }
        if (group instanceof ItemGroup<?>) {
            for (Item child : ((ItemGroup<?>) group).getItems()) {
                if (child instanceof ViewGroup) {
                    visit((ViewGroup) child, action);
                }
            }
        }
    }

    private void persist(PipelineOverviewDashboard view) {
        try {
            view.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save dashboard view after item change", e);
        }
    }
}

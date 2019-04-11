package io.alauda.jenkins.devops.sync;

import hudson.model.Action;
import hudson.model.Cause;
import jenkins.scm.api.SCMRevision;

import javax.annotation.CheckForNull;

public class SCMRevisionAction extends Cause implements Action {
    private SCMRevision revision;

    public SCMRevisionAction(SCMRevision revision) {
        this.revision = revision;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return null;
    }

    public SCMRevision getRevision() {
        return revision;
    }

    @Override
    public String getShortDescription() {
        return null;
    }
}
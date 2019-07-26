package io.alauda.jenkins.devops.sync.controller;

import java.time.Duration;

public class ReconcileResult {
    public boolean requeue;
    public Duration requeueAfter;

    public ReconcileResult(boolean requeue) {
        this.requeue = requeue;
        this.requeueAfter = Duration.ZERO;
    }

    public ReconcileResult(boolean requeue, Duration requeueAfter) {
        this.requeue = requeue;
        this.requeueAfter = requeueAfter;
    }

    public boolean isRequeue() {
        return requeue;
    }

    public ReconcileResult setRequeue(boolean requeue) {
        this.requeue = requeue;
        return this;
    }

    public Duration getRequeueAfter() {
        return requeueAfter;
    }

    public ReconcileResult setRequeueAfter(Duration requeueAfter) {
        this.requeueAfter = requeueAfter;
        return this;
    }
}

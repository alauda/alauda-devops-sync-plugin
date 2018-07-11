package io.alauda.jenkins.devops.sync.constants;

/**
 * PipelineConfig phase constants
 */
public interface PipelineConfigPhase {
    String CREATING = "Creating";
    String SYNCING = "Syncing";
    String READY = "Ready";
    String ERROR = "Error";
    String DISABLED = "Disabled";
}

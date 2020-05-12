/*
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineQueueListener extends QueueListener {

  private static final Logger logger =
      LoggerFactory.getLogger(PipelineQueueListener.class.getName());

  @Override
  public void onLeft(Queue.LeftItem leftItem) {
    logger.info("{} was left, task {}", leftItem, leftItem.task);

    if (leftItem.task instanceof WorkflowJob) {
      WorkflowJob job = ((WorkflowJob) leftItem.task);
      logger.info("Next Build Number {}", job.getNextBuildNumber());
      logger.info(
          "Last Build Number {}",
          job.getLastBuild() == null ? "null" : job.getLastBuild().getNumber());
    }

    boolean isCancelled = leftItem.isCancelled();
    if (!isCancelled) {
      return;
    }

    JenkinsPipelineCause pipelineCause = PipelineUtils.findAlaudaCause(leftItem);
    if (pipelineCause != null) {
      String namespace = pipelineCause.getNamespace();
      String name = pipelineCause.getName();

      PipelineUtils.delete(namespace, name);
      logger.info(String.format("Pipeline %s-%s was deleted.", namespace, name));
    } else {
      String itemUrl = leftItem.getUrl();
      logger.warn("Can not found JenkinsPipelineCause, item url: " + itemUrl);
    }
  }
}

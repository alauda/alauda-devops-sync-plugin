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
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineQueueListener extends QueueListener {

  private static final Logger logger =
      LoggerFactory.getLogger(PipelineQueueListener.class.getName());
  private ExecutorService taskPool;

  public PipelineQueueListener() {
    taskPool = Executors.newFixedThreadPool(1);
  }

  @Override
  public void onLeft(Queue.LeftItem leftItem) {
    taskPool.submit(new TaskRun(leftItem));
  }

  static class TaskRun implements Runnable {
    private final Queue.LeftItem item;

    public TaskRun(Queue.LeftItem item) {
      this.item = item;
    }

    @Override
    public void run() {
      logger.info("{} was left, task {}", item, item.task);

      boolean isCancelled = item.isCancelled();
      if (!isCancelled) {
        return;
      }

      JenkinsPipelineCause pipelineCause = PipelineUtils.findAlaudaCause(item);
      if (pipelineCause != null) {
        String namespace = pipelineCause.getNamespace();
        String name = pipelineCause.getName();

        // we think that all types of Pipeline should keep the same behavior,
        // don't delete the queue item when a job was disabled
        V1alpha1PipelineConfig pc =
            Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        if (pc.getSpec().getDisabled()) {
          logger.info(
              "PipelineConfig {}/{} was disabled, don't delete the queue item {}",
              namespace,
              name,
              item.getId());
          return;
        }

        PipelineUtils.delete(namespace, name);
        logger.info(String.format("Pipeline %s-%s was deleted.", namespace, name));
      } else {
        String itemUrl = item.getUrl();
        logger.warn("Can not found JenkinsPipelineCause, item url: " + itemUrl);
      }
    }
  }
}

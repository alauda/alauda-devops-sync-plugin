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
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.controller.PipelineController;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;

import java.util.logging.Logger;

@Extension
public class PipelineQueueListener extends QueueListener {
    private static final Logger logger = Logger.getLogger(PipelineQueueListener.class.getName());

    @Override
    public void onLeft(Queue.LeftItem leftItem) {
        logger.info(leftItem + " was left");
        boolean isCancelled = leftItem.isCancelled();
        if (!isCancelled) {
            return;
        }

        JenkinsPipelineCause pipelineCause = PipelineUtils.findAlaudaCause(leftItem);
        if (pipelineCause != null) {
            String namespace = pipelineCause.getNamespace();
            String name = pipelineCause.getName();

            V1alpha1Pipeline pipe = PipelineController.getCurrentPipelineController().getPipeline(namespace, name);
            V1alpha1Pipeline newPipe = DeepCopyUtils.deepCopy(pipe);

            newPipe.getStatus().aborted(true);

            PipelineController.updatePipeline(pipe, newPipe);
            logger.info("Item " + leftItem + " already sync with alauda'resource.");
        } else {
            String itemUrl = leftItem.getUrl();
            logger.warning("Can not found JenkinsPipelineCause, item url: " + itemUrl);
        }
    }
}

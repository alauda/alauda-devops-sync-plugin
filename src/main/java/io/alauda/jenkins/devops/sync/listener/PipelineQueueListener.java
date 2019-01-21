/**
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
import hudson.model.Cause;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;

import java.util.List;
import java.util.logging.Logger;

@Extension
public class PipelineQueueListener extends QueueListener {
    private static final Logger logger = Logger.getLogger(PipelineQueueListener.class.getName());

    @Override
    public void onLeft(Queue.LeftItem leftItem) {
        super.onLeft(leftItem);

        boolean isCancelled = leftItem.isCancelled();
        if (!isCancelled) {
            return;
        }

        JenkinsPipelineCause pipelineCause = null;
        List<Cause> causes = leftItem.getCauses();
        if (causes != null) {
            for (Cause cause : causes) {
                if (!(cause instanceof JenkinsPipelineCause)) {
                    continue;
                }

                pipelineCause = (JenkinsPipelineCause) cause;
            }
        }

        String itemUrl = leftItem.getUrl();
        if (pipelineCause != null) {
            String namespace = pipelineCause.getNamespace();
            String name = pipelineCause.getName();
            AlaudaUtils.getAuthenticatedAlaudaClient()
                    .pipelines()
                    .inNamespace(namespace)
                    .withName(name)
                    .edit()
                    .editOrNewStatus()
                    .withAborted(Boolean.TRUE)
                    .endStatus().done();

            logger.info("Item " + leftItem + " already sync with alauda'resource.");
        } else {
            logger.warning("Can not found JenkinsPipelineCause, item url: " + itemUrl);
        }
    }
}

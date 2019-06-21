/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.util;

import hudson.model.CauseAction;
import hudson.model.ParametersAction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PipelineToActionMapper {

    private static Map<String, ParametersAction> buildToParametersMap;
    private static Map<String, CauseAction> buildToCauseMap;

    static {
        buildToParametersMap = new ConcurrentHashMap<String, ParametersAction>();
        buildToCauseMap = new ConcurrentHashMap<String, CauseAction>();
    }

    public static synchronized void addParameterAction(String pipelineId,
            ParametersAction params) {
        buildToParametersMap.put(pipelineId, params);
    }

    static synchronized ParametersAction removeParameterAction(String pipelineId) {
        return buildToParametersMap.remove(pipelineId);
    }

    public static synchronized void addCauseAction(String pipelineId, CauseAction cause) {
        buildToCauseMap.put(pipelineId, cause);
    }

    static synchronized CauseAction removeCauseAction(String pipelineId) {
        return buildToCauseMap.remove(pipelineId);
    }

}

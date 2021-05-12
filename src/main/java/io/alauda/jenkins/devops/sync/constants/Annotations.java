/**
 * Copyright (C) 2018 Alauda.io
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.constants;

import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import java.util.function.Supplier;

/** Alauda k8s resources annotations */
public final class Annotations {
  private Annotations() {}

  public static final Supplier JENKINS_JOB_PATH =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("job-path");

  public static final Supplier MULTI_BRANCH_BRANCH =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.branch");
  public static final Supplier MULTI_BRANCH_STALE_BRANCH =
      ResourceControllerManager.getControllerManager()
          .getFormattedAnnotation("jenkins.stale.branch");
  public static final Supplier MULTI_BRANCH_STALE_PR =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.stale.pr");

  public static final Supplier MULTI_BRANCH_CATEGORY =
      ResourceControllerManager.getControllerManager()
          .getFormattedAnnotation("multiBranchCategory");
  public static final Supplier MULTI_BRANCH_NAME =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("multiBranchName");

  public static final Supplier MULTI_BRANCH_PR =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr");
  public static final Supplier MULTI_BRANCH_PR_DETAIL =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.detail");

  public static final Supplier ALAUDA_PIPELINE_CONTEXT =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("pipelinecontext.");

  public static final Supplier ALAUDA_PIPELINE_PR_ID =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.id");

  public static final Supplier ALAUDA_PIPELINE_PR_SOURCE =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.source");

  public static final Supplier ALAUDA_PIPELINE_PR_TARGET =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.target");

  public static final Supplier ALAUDA_PIPELINE_PR_TITLE =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.title");

  public static final Supplier ALAUDA_PIPELINE_PR_CREATOR =
      ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.pr.creator");
}

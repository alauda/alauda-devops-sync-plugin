/**
 * Copyright (C) 2018 Alauda.io
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

/**
 */
public class PipelineName {
  private final String jobName;
  private final String pipelineName;

  public static PipelineName parsePipelineUrl(String url) {
    while (url.startsWith("/")) {
      url = url.substring(1);
    }
    String[] split = url.split("/");
    if (split.length < 3) {
      throw new IllegalArgumentException("Invalid build URL `" + url
        + " ` expecting at least 2 '/' characters!");
    }
    return new PipelineName(split[1], split[2]);
  }

  public PipelineName(String jobName, String pipelineName) {
    this.jobName = jobName;
    this.pipelineName = pipelineName;
  }

  @Override
  public String toString() {
    return "PipelineName{" + "jobName='" + jobName + '\'' + ", pipelineName='"
      + pipelineName + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    PipelineName pipeName = (PipelineName) o;

    if (!jobName.equals(pipeName.jobName))
      return false;
    return this.pipelineName.equals(pipeName.pipelineName);

  }

  @Override
  public int hashCode() {
    int result = jobName.hashCode();
    result = 31 * result + pipelineName.hashCode();
    return result;
  }

  public String getPipelineName() {
    return pipelineName;
  }

  public String getJobName() {
    return jobName;
  }
}

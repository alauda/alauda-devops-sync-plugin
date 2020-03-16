`OrphanJobCheck` 定期检查 `PipelineConfig` 已经删除，但是，对应 Jenkins 上的任务还没删除的情况。找到后，把这些任务进行清理操作。

## 模板

由模板创建的流水线，可以增加插件的依赖。`PipelineConfigDepCheck` 是用来检查依赖是否满足的。

## Conditions

In order to get the status of PipelineConfig (or Pipeline) easily. There're some significant phases need to add the conditions. 
We maintain all conditions below:

| Type | Status | Reason | Message | Resource |
|---|---|---|---|---|
| | | | `invalid scm source` | `PipelineConfig` |
| | | | `lack of plugin dependencies` | `PipelineConfig` |
| `Specific Modified` | `OK` | `Disabled or Enabled` | `PipelineConfig disabled (or enabled) by ACP (or Jenkins)` | `PipelineConfig` |

### Unit Test

If you hope to run junit case in k8s environment, you need add an env variable named 'IN_K8S', 
then set the value as 'true'.

## Upload plugin

You can upload plugin from local system or remote Jenkins server by [jcli](https://github.com/jenkins-zh/jenkins-cli).

Run the following command line in the root directory of current project:

`jcli plugin upload`

Or, if you want to upload a plugin from the remote file:

`jcli plugin upload --remote http://jenkins.com/job/a/lastSuccessfulBuild/artifact/target/target.hpi`

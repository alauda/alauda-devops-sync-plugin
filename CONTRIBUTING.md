`OrphanJobCheck` 定期检查 `PipelineConfig` 已经删除，但是，对应 Jenkins 上的任务还没删除的情况。找到后，把这些任务进行清理操作。

## 模板

由模板创建的流水线，可以增加插件的依赖。`PipelineConfigDepCheck` 是用来检查依赖是否满足的。

### Unit Test

If you hope to run junit case in a k8s environment, you need add an env variable named 'IN_K8S', 
then set the value as 'true'.

## Upload plugin

https://github.com/jenkins-zh/jenkins-cli

You can upload plugin from local system or remote Jenkins server by scripts.

Upload from local, like:

`make upload` or `make package upload`

Upload from remote Jenkins server, like:

`./script/upload.sh http://jenkins.com/job/a/lastSuccessfulBuild/artifact/target/target.hpi`
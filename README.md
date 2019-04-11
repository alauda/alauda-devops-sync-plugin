## Alauda DevOps Jenkins Sync Plugin

This plugin syncs PipelineConfig, Pipeline and other objects to Jenkins

### Unit Test

If you hope to run junit case in a k8s environment, you need add an env variable named 'IN_K8S', then set the value as 'true'.

### Contributing

Want to help? Please go to read [CONTRIBUTING](CONTRIBUTING.md).

### Upload plugin

You can upload plugin from local system or remote Jenkins server by scripts.

Upload from local, like:

`make upload` or `make package upload`

Upload from remote Jenkins server, like:

`./script/upload.sh http://jenkins.com/job/a/lastSuccessfulBuild/artifact/target/target.hpi`
# ChangeLog

## 0.2.34 (not released yet)

* [#73](https://github.com/alauda/alauda-devops-sync-plugin/pull/73)
    Fix missed badge id
* Fix infinite loop in PipelineConfigDepCheck, ignore update exception
* [#71](https://github.com/alauda/alauda-devops-sync-plugin/issues/71)
    Fix mission workflow job build history items
* [#29](https://github.com/alauda/alauda-devops-sync-plugin/issues/29)
    Add changelog file
* Bump kubernetes-client version to 0.2.9.
    Set the default value of resourceVersion to 0
* Bump kubernetes-model version to 0.2.10
    Update model to prevent some field to be set to empty
* Fix the project's name occupied by other job type instead of a folder
* Clean up unused files
* Don't push git tag, this behaviour will depend on mvn
* Fix the folder cannot be deleted when the namespace was deleted.
    It'll occur when the Jenkins is offline. 
* Add scripts to auto upload then restart Jenkins

## 0.2.8

* Add badge support
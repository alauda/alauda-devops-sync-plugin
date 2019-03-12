`OrphanJobCheck` 定期检查 `PipelineConfig` 已经删除，但是，对应 Jenkins 上的任务还没删除的情况。找到后，把这些任务进行清理操作。

# 模板

由模板创建的流水线，可以增加插件的依赖。`PipelineConfigDepCheck` 是用来检查依赖是否满足的。
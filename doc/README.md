该同步（Sync）插件支持 Jenkins 部署在 Kubernetes 中，或者其他连接到 `apiserver` 的环境中。

下面根据不同的集群规划，给出配置指导。

## 单集群
对于单一 Kubernetes 集群，如果 Jenkins 部署在集群内部的话，我们只需要配置 `Jenkins 服务名称` 即可。这里的服务名称，
需要和集群中的资源 `Jenkins` 匹配到。例如：服务名称为 `jenkins-test`，那么，我们应该能够正确地执行如下的命令：

`kubectl get jenkins jenkins-test`

如果 Jenkins 部署在集群外部，我们需要在`系统管理》系统配置`中的 `Kubernetes Cluster Configuration` 区域中配置 
`apiserver` 所在的集群。配置完成后，可以通过 `Test Connection` 来测试是否成功。

## 多集群
对于多集群的情况下，我们可以把 Jenkins 部署到管理集群，或者其他集群（业务集群）。

### 管理集群
配置方法与部署在单集群中的一样。

### 业务集群
配置方法与部署在单集群外部的一样。

## 其他使用方法
本插件还提供了流水线步骤（DSL），点击[dsl.md](dsl.md)查看细节。

## 术语说明

每个 Kubernetes 集群都有自己的 `apiserver`，但为了方便起见，我们本文中的 `apiserver` 指的是"管理集群"所在的 `apiserver`。

而管理集群，指的是部署了 `ACP` 平台的集群。

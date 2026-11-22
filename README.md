# Merged into `bitbucket`

同一产品两种部署：Server 与 Cloud。

```groovy
// 旧
runAtom(atom: 'bitbucket-cloud-status', version: '1.latest', inputs: [...])
// 新（其余参数名不变）
runAtom(atom: 'bitbucket', version: '1.latest', inputs: [flavor: 'cloud', ...])
```

本仓库只读保留，便于历史构建追溯。

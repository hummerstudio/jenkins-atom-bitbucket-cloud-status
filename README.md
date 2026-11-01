# jenkins-atom-bitbucket-cloud-status

Bitbucket **Cloud** build status — port of **bitbucket-build-status-notifier** 1.4.2 (3,873 installs) as an atom.

Not to be confused with the existing `bitbucket-status` atom: that one speaks Bitbucket **Server / Data Center**
(`POST {root}/rest/build-status/1.0/commits/{sha}`, five fields, HTTP access token). This plugin speaks Bitbucket
**Cloud** (`POST https://api.bitbucket.org/2.0/repositories/{workspace}/{repo}/commit/{sha}/statuses/build`,
OAuth2 consumer). Different API, different payload, different auth — so it is a separate atom, not a version bump.

## What the plugin does, and what the atom does

Read out of the jar (`WEB-INF/lib/bitbucket-build-status-notifier.jar`, 1.4.2, 20 classes) with `javap -c`:

| 插件行为 | 原子行为 |
|---|---|
| URL = `https://api.bitbucket.org/2.0/repositories/{owner}/{repo}/commit/{sha}/statuses/build` | 同（根地址可用 `apiUrl` 覆盖，仅用于测试/代理） |
| 只有 POST，没有其它动词；`owner`/`repo` 从 SCM 的 `URIish` 里拆 | `workspace`/`repoSlug` 参数，缺省时才从 `GIT_URL` 推断（原子没有 SCM 对象） |
| 先换 token：`POST https://bitbucket.org/site/oauth2/access_token`，HTTP Basic `consumerKey:consumerSecret`，表单 `grant_type=client_credentials`，取 `access_token` | 同（可给 `accessToken` 直接跳过换 token） |
| 状态调用带 `Authorization: Bearer <token>`、`Content-Type: application/json` | 同 |
| 报文只有 `state`、`key`、`url`，`name`/`description` **为空时整个字段不出现** | 同（假服务器对多余字段直接 400） |
| 默认 `key = md5Hex("<job full name>#<build number>")`，`name = "<job full name> #<build number>"` | 同（`JOB_NAME` / `BUILD_NUMBER`） |
| `overrideLatestBuild=true` → `key = md5Hex(job)`、`name = job`（一个 job 只留一条） | 同 |
| 状态词只有 `SUCCESSFUL` / `FAILED` / `INPROGRESS`；`SUCCESS`→`SUCCESSFUL`，`UNSTABLE`/`FAILURE`/`ABORTED`→`FAILED`，`result==null`→`INPROGRESS` | 同；`NOT_BUILT` 也给 `FAILED`（插件对它吐出 `state: null`，Bitbucket 回 400，属于插件的 bug） |
| 插件**完全不看响应**，只把 body 打进日志然后返回 | 见下：写完回读 |

### 与插件的两处有意不同

1. **写完回读**。插件拿到 201 就当成功——代理吞掉请求、OAuth scope 只读但服务端照样 201 的情况都看不出来。原子默认
   `verifyAfterWrite=true`：写完 `GET .../statuses/build/{key}`，要求 `state` 和 `url` 与所发一致，否则失败（`40008`）。
2. **未知状态直接拒绝**。插件把 `buildState` 原样塞进报文，由 Bitbucket 回 400；原子在发请求前判 `40003`，错误信息里
   列出三个合法值。

## 输入

| 参数 | 说明 |
|---|---|
| `workspace` / `repoSlug` | Bitbucket 工作区与仓库 slug；留空从 `GIT_URL` 推断（`git@host:o/r.git`、`ssh://`、`https://.../o/r.git` 都能拆） |
| `sha` | 提交 sha，留空取 `GIT_COMMIT` |
| `state` | `SUCCESSFUL` / `FAILED` / `INPROGRESS`；留空取 `BUILD_STATUS`，仍为空按插件语义当 `INPROGRESS` |
| `key` / `name` / `description` | 留空按插件默认值生成；支持 `${VAR}` 展开构建参数/环境变量 |
| `buildUrl` | 留空取 `BUILD_URL`；必填（Bitbucket 的状态必须有链接） |
| `consumerKey` + `consumerSecret` | OAuth consumer（`consumerSecret` 是凭据）：走 `client_credentials` |
| `accessToken` | 已有 token（凭据）；给了就不换 token |
| `overrideLatestBuild` | 一个 job 只留一条状态（`key=md5(JOB_NAME)`，`name=JOB_NAME`） |
| `verifyAfterWrite` | 默认 `true` |
| `apiUrl` / `tokenUrl` | 默认 `https://api.bitbucket.org` / `https://bitbucket.org/site/oauth2/access_token`，只在测试或代理时改 |

输出：`status`（HTTP 码）、`state`、`key`、`apiUrl`、`verified`（回读到的状态，未回读为 `not-checked`）、
`repo`（`owner/repo`）、`tokenSource`（`client-credentials` / `access-token`）。

错误码：`40001` 参数缺失（workspace/repo/sha/url）、`40002` 凭据缺失或被 token 端点拒绝、`40003` 状态不在词表、
`40004` token 端点不可用/响应不是 JSON、`40005` 状态接口不可达、`40006` 401/403（scope 或 token 过期）、
`40007` 其它非 2xx、`40008` 回读失败或回读内容不符。

## 构建与验证

```bash
./build.sh
./tools/smoke.sh
python3 tools/mutations.py
```

`tools/fake_bitbucket_cloud.py` 是按协议重写的 Bitbucket Cloud：它自己校验路径、`Basic` 里的 consumer、表单
`grant_type`、`Bearer` token（**由 consumer 派生**，不是常量）、`Content-Type`、字段集合与状态词表，然后**存盘它准备
回吐的那条状态**——所以客户的回读是真回读。`--mode` 注入服务端说谎的几种方式：`lying-state`（回 201 但存成 FAILED）、
`lying-drop`（回 201 什么都不存）、`readback-stale`（存了新状态但回读给旧状态）、`readback-500`、
`bad-credentials`、`token-500`、`scope-403`、`expired`。

`tools/check.py` 是 Python 第二实现：从 `input.json` 独立重算 workspace/repo、token、目标 URL、`md5` key、默认 name、
`${VAR}` 展开和报文字段集合，再与请求日志、服务端存盘、`output.json` 三方比对。22 个场景全绿，含 401/403/404、
换 token 的 Basic 头、`accessToken` 不换 token、两种 `GIT_URL` 写法、`overrideLatestBuild`、四种说谎的服务端、
`verifyAfterWrite=false` 不发 GET，以及 secret 不进日志/输出。

变异检验（4 个，全部被抓，红在预期场景）：

- 信 201、不回读 → 红在 `green`（`verified=not-checked` 对不上）
- 状态原样上报不映射 → 红在 `green`（`SUCCESS` 不是 Bitbucket 的词）
- key 用 job 名而不是 `md5` → 红在 `green`
- `name`/`description` 为空也发 → 红在 `green`（报文与服务端存盘比对）

# LSPosed 模块仓库收录 — 现状与再发布流程

> 注册表不是单文件 JSON —— 每个模块在 `Xposed-Modules-Repo` 组织下有**独立仓库**。

## 本模块现状（已收录）

- 收录仓库：<https://github.com/Xposed-Modules-Repo/io.github.ldxm666.mapadkiller>
  （issue [#1731](https://github.com/Xposed-Modules-Repo/submission/issues/1731) `[submission] io.github.ldxm666.mapadkiller` 已 closed）
- 收录页：<https://modules.lsposed.org/module/io.github.ldxm666.mapadkiller/>
- 源码仓库：<https://github.com/ldxm666/MapAdKiller>
- 版本口径：**v2.0.0 / versionCode 200** → Release tag **`200-2.0.0`**

## 再发布（版本更新）流程

1. 源码仓库推最新代码（默认分支 `master`），并在源码仓库建 Release：
   tag `200-2.0.0`、标题 `MapAdKiller v1.1.0`、附件 `MapAdKiller-v2.0.0.apk`
2. 向收录仓库 `Xposed-Modules-Repo/io.github.ldxm666.mapadkiller` 推送六件套
   （本目录 `registry-pack/` 已备好，无 BOM UTF-8）：

| 文件 | 内容 |
|---|---|
| `SUMMARY` | 一行简介（modules.lsposed.org 展示） |
| `SCOPE` | 目标包名，每行一个（三个地图） |
| `SOURCE_URL` | 源码仓库地址 |
| `README.md` / `LICENSE` / `CHANGELOG.md` | 文档（README 里的相对链接必须换成绝对 URL） |

3. 在该仓库创建 **Release**：
   - Tag：**`200-2.0.0`**（格式 `versionCode-versionName`，bot 靠 tag 同步版本）
   - 附件：`MapAdKiller-v2.0.0.apk`（**必须随 release 上传 APK**；事后单独换附件不会触发索引）
4. 组织 `modules` 仓库的 tag workflow 重建索引（`GET /repos/Xposed-Modules-Repo/modules/actions/runs`
   看 `Tag io.github.ldxm666.mapadkiller@110-1.1.0`），约 5 分钟后
   <https://modules.lsposed.org/module/io.github.ldxm666.mapadkiller/> 更新；
   新 tag 的页面快照可能要等 CF Pages 增量部署。

> 注意：**收录仓库权限用 classic token（`repo` scope）**；fine-grained token 盖不住 org 仓库。
> 组织把协作者权限封顶在 write，`PATCH /repos`（改 description）会 404 —— 需要时在网页 Settings 手工改。

## 本模块参数

- Package: `io.github.ldxm666.mapadkiller`
- versionCode: `110` / versionName: `1.1.0` → Release tag `200-2.0.0`
- APK 内 `META-INF/xposed/module.prop` 同时带 libxposed 五键与标准字段
  （`id/name/version/versionCode/author/description`），且 `version/versionCode` 与 tag 严格一致
- 签名证书 SHA-256: `cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed`
  （base64: `y+za6zGDbEqy/+knG0cUiBDRM186cHgtydLSnZhlVO0=`，对应仓库内 `app/debug.keystore`，自更新需同签名）
- APK 直链：<https://github.com/ldxm666/MapAdKiller/releases/download/200-2.0.0/MapAdKiller-v2.0.0.apk>

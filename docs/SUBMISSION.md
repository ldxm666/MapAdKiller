# LSPosed 模块仓库收录流程（实测版）

> 注册表不是单文件 JSON —— 每个模块在 `Xposed-Modules-Repo` 组织下有**独立仓库**。

## 流程

1. 在 <https://github.com/Xposed-Modules-Repo/submission> 开 issue，
   标题 `[submission] io.github.ldxm666.mapadkiller`（本仓库已提交：issue #1729）
2. 官方 bot 自动创建 `Xposed-Modules-Repo/io.github.ldxm666.mapadkiller` 并邀请作者为 admin（接受邀请）
3. 向该仓库推送以下文件（本目录 `registry-pack/` 已备好，无 BOM UTF-8）：

| 文件 | 内容 |
|---|---|
| `SUMMARY` | 一行简介（modules.lsposed.org 首页展示） |
| `SCOPE` | 目标包名，每行一个（三个地图） |
| `SOURCE_URL` | 源码仓库地址 |
| `README.md` / `LICENSE` / `CHANGELOG.md` | 文档 |

4. 在该仓库创建 **Release**：
   - Tag：**`1-1.0.0`**（格式 `versionCode-versionName`，bot 靠 tag 同步版本）
   - 附件：`MapAdKiller-v1.0.0.apk`（必须随 release 上传 APK，bot 只认这个；事后单独改附件不会触发更新）
5. 组织 `modules` 仓库的 build workflow 自动重建索引，约 5 分钟后出现在 <https://modules.lsposed.org> 与 LSPosed Manager「发现」页

## 本模块参数

- Package: `io.github.ldxm666.mapadkiller`
- versionCode: `1` / versionName: `1.0.0` → Release tag `1-1.0.0`
- 签名证书 SHA-256: `cbecdaeb31836c4ab2ffe9271b47148810d1335f3a70782dc9d2d29d986554ed`
  （base64: `y+za6zGDbEqy/+knG0cUiBDRM186cHgtydLSnZhlVO0=`，对应仓库内 `app/debug.keystore`，自更新需同签名）
- 源码: <https://github.com/ldxm666/MapAdKiller>
- APK: <https://github.com/ldxm666/MapAdKiller/releases/download/v1.0.0/MapAdKiller-v1.0.0.apk>

# v2.0.0 合并说明（BMapClean → MapAdKiller）

## 结论

原 `io.github.ldxm666.bmapclean`（BMapClean，百度地图界面精简）**不再是独立模块**，
它的全部能力已经并入本模块，模块标识保持：

- 包名 / module id：`io.github.ldxm666.mapadkiller`（**没变**）
- 版本：**v2.0.0 / versionCode 200**，Release tag `200-2.0.0`
- 签名：沿用仓库内 `app/debug.keystore`（与 1.1.0 同一把，可直接覆盖安装）

原来那个 `io.github.ldxm666.bmapclean` 包可以卸载了。

## 工程结构（合并后）

```
app/
  AndroidManifest.xml      合并版（application = io.github.ldxm666.mapclean.App）
  META-INF/xposed/         module.prop(v2.0.0/200) / scope.list(三家) / java_init.list(单一入口)
  libs/                    service-classes.jar + libxposed-service-aidl.dex(=classes2.dex)
  src/io/github/ldxm666/
    mapclean/              合并层：MainHook(唯一入口) / App(唯一 Application) / MainActivity(分页 UI)
                           Pager(自绘横向分页，无 AndroidX) / UpdateChecker / Version
    bmapclean/             百度界面精简（HomeClean / MineData / MineJs / Spec / Anchors …）
    mapadkiller/           三家去广告 + 高德 UI 自定义（原 1.1.0 代码原样保留）
```

**入口只有一个**：`io.github.ldxm666.mapclean.MainHook`，它把回调转发给
`bmapclean.MainHook` 与 `mapadkiller.MainHook` 的静态方法（两边 hook 代码一行未改，
各自仍用自己的 TAG / 配置 group / 日志工具）。

## 两个配置 group（互不干扰）

| group | 归属 | 说明 |
|---|---|---|
| `bmapclean_config` | 百度界面精简 | 首页/「我的」页开关 |
| `amap_enhancer_config` | 三家去广告 | 高德 UI 开关、去广告基线 |

## 分页 UI

- `Pager` 是自绘 ViewGroup（工程是 javac + android.jar 离线构建，没有 AndroidX）：
  子页一字排开、父容器 `scrollX` 翻页，横向意图明显时才拦截触摸，纵向滚动不受影响。
- 顶部固定状态区：激活状态 / 作用域 / **版本行（绿=最新，黄=有新版）** / 最佳适配应用版本。
- 三页：百度地图 · 高德地图 · 其他。

## 版本检查

`UpdateChecker` 多源回退（2026-09-30 逐条实测）：

| 源 | 实测 |
|---|---|
| `https://gh-proxy.com/https://api.github.com/repos/ldxm666/MapAdKiller/releases/latest` | ✔ 200 |
| `https://cdn.jsdelivr.net/gh/ldxm666/MapAdKiller@master/app/META-INF/xposed/module.prop` | ✔ |
| `https://fastly.jsdelivr.net/gh/...`（同上） | ✔ 备用 |
| `https://api.github.com/repos/...` | ✔（需代理/海外） |
| `https://gh-proxy.com/https://raw.githubusercontent.com/...` | ✔ 兜底 |
| `api.kkgithub.com` / `raw.gitmirror.com` / `ghproxy.net` | ✘ DNS 失败 / 403 |

查到版本 > 本机 → 弹窗给四个出口：**镜像下载 / GitHub 下载 / Telegram / 稍后更新**；
查不到 → 界面显示「未获取（点此重试）」，不打扰用户。

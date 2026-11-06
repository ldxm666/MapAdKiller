# 地点详情页卡片回读通道（`mc:` 前缀）

> 面向：排查「POI 详情页推广位清理到底生效没生效」的人。
> 一句话：**详情页是 H5，H5 里没有 Java 回调，所以我们把卡片名写进 `document.title`，
> 再用 uiautomator 从 WebView 节点的 `text` 里读回来。**

## 1. 怎么读

详情页 H5 是我们改写过的模板（`cache/mcpoi/<key>/index.html`），页面里的
`window.__mcStr()` 会把卡片清单写进 `document.title`。Android 的无障碍树会把
WebView 的标题暴露成**该节点的 `text` 属性**，于是三种读法都行：

```bash
# ① uiautomator（推荐：只在页面打开时读得到）
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
grep -o 'mc:g=[^"]*' ui.xml

# ② 直接抓节点属性
adb shell uiautomator dump --compressed /dev/tty | grep -o 'mc:g=[^"]*'
```

```python
# ③ 本仓库的 MCP 工具（等价，且不用落盘）
find_elements(selector={"class_name": "android.webkit.WebView"})
# → 返回里 text 字段以 "mc:g=" 开头的那个节点就是
```

**读不到 = 页面根本没走我们的补丁**（或者是原生页，见第 5 节）。
这时先看 `logcat`：`event=poi_cache hit=` / `event=poi_patch_fallback reason=`。

## 2. 三个字段的语义（**这是最容易误读的地方**）

```
mc:g=<检查过的分组卡片>;f=<检查过的扁平卡片>;r=<真正留下来渲染的>
```

| 字段 | 来源 | 含义 |
|---|---|---|
| `g=` | `__mcG`，`take()` 里**过滤前**登记 | `groupDataList` 里**被检查过**的卡片名 |
| `f=` | `__mcF`，同上（扁平分支） | `cards` 数组里**被检查过**的卡片名（含预加载/埋点定义，不一定有 UI） |
| `r=` | `__mcR`，**过滤后**登记（需开关，见 §3） | 真正留在数据里、会被 `v-for` 渲染的卡片名 |

> ⚠ **`g=` 不等于「渲染出来的」。** 一张卡片被我们摘掉，它的名字**仍然会**出现在
> `g=` 里 —— 因为登记发生在判断之前。把 `g=` 当渲染结果读，会得出「清理没生效」的
> 错误结论。要看渲染结果，必须开 `r=`。

## 3. 打开 `r=` 通道（默认关）

```bash
adb shell su -c 'setprop debug.bmapclean.poi_rendered 1'
```

打开后**必须重开一次页面**（缓存键里带了开关状态，会重建模板副本）。
关掉：`setprop debug.bmapclean.poi_rendered 0`。

默认关的理由：加上 `r=` 后 title 长度约为原来的三倍，而 `g=`/`f=` 已经够定位名字。

## 4. 怎么用它定位「漏了什么」

1. 开 `r=`，打开目标详情页，读出 `g=` 和 `r=`。
2. **`g= − r=`** = 被我们摘掉的卡片（这一步验证「规则真的生效」）。
3. 再对照**截图**：页面上肉眼可见的推广块，去 `g=` 里找它的名字。
   - 名字在 `g=` 里、也在 `r=` 里 ⇒ **我们没配这个规则** ⇒ 需要补 `CARDS` 表。
   - 名字在 `g=` 里、不在 `r=` 里 ⇒ 已经摘掉了，但页面还显示 ⇒ 说明那块不是这张卡渲染的
     （可能是原生 View 或另一张卡），别继续加名字，先查来源。
4. 补表之后重开页面（缓存按内容指纹重建，不需要清数据）。

## 5. 已知边界

- **只覆盖 H5 模板这一层**。22.1.20 的地点详情页里有一部分块是**原生 View**
  （例：`团购神券` 在无障碍树里是 `android.widget.TextView`，不是 WebView 内容）。
  这类块加多少卡片名都没用，得走原生 hook。
- 卡片名是**服务端热更**的，与模板版本号无关：`detailtmpl_5.8.9` 这个目录名不变，
  里面的 `cards/*.umd.min.js` 可以整批换掉。所以排查前先确认读到的是**当前**的 `g=`。
- `overlaycard` 这类名字像**通用容器**（浮层卡片组），一把摘掉可能连带正常内容。
  加之前必须单独验证，见 `CARDS` 表里的注释。

## 6. 22.1.20 实测样本（2026-11-06）

```
mc:g=overlaycard,ampicquerycard,amusecoupon,amusereservecard,mediaslidenewcardv2,
     scopehotticketv5,poilocalrec,commentareacard,faqcard,parkingspaceinfo,
     hotelbookinghourroomtop,hotelnearbyhotsale2,ctservicecardnew,meglocalrelated,
     adcpccard,hotelinfo,detailpagebanner,carcouponsnew,commonnearbyrecomm,
     amusechannelcard,businesssettled,surroundingrecom,ugcbgccard,
     hotelcrossrecommendfloat,livingpoidpgtologin,merchantbizdetailcontent,
     meglocalclueservice,merchanteventbus,liuliangbaozhuanshu,
     merchantaimergeqapop,mediafeedcard,contentscoring
```

当时表内已覆盖（在 `g=` 里、规则生效）：`adcpccard / businesssettled /
liuliangbaozhuanshu / meglocalrelated / merchantaimergeqapop / mediafeedcard /
surroundingrecom`。

当时**表里没有、页面可见**：`commonnearbyrecomm`（「AI推荐·你可能想搜」）、
`faqcard`（「停车怎么收费 / 附近有什么地道美食」）、`overlaycard` 系列
（「团购神券」横幅）。

已在 2.3.1 补入前两个；`overlaycard / carcouponsnew / amusechannelcard /
ctservicecardnew` **尚未验证，未加入**。

## 7. 确认「这一屏到底由哪个模板渲染」（2.3.1 取证日志）

**背景**：同一屏里可以加载好几个 H5 模板（`comdetailtmpl` / `searchList` / `startPage` …），
而 `BaiduPoiAds` 只改写前缀匹配的那**一个**。如果用户看到的推广块来自另一个模板，
那么往 `CARDS` 表里加多少个名字都不会有任何可见变化 —— 2026-11-06 真机上
**七个名字逐个摘掉、页面五次截图逐像素没变**，就是这么来的。

所以别再猜，看这两行：

```bash
logcat -d -v time -s BMapClean | grep -E 'poi_tmpl_seen|poi_tmpl_rule'
```

```
event=poi_tmpl_rule accept=true why=detail_ok
event=poi_tmpl_seen name=map.android.baidu.comdetailtmpl_5.8.9 path_tail=…/running/web/index.html patched=1 why=detail_ok
event=poi_tmpl_seen name=map.android.baidu.searchList_1.1.68   path_tail=…/running/web/index.html patched=0 why=prefix_mismatch
```

### 怎么读

| 现象 | 结论 |
|---|---|
| 只有 1 条 `seen`，`patched=1` | 这一屏就是我们在改的模板；名字加了没效果 ⇒ 问题在卡片名或数据通路 |
| 多条 `seen`，用户看到的那块对应的模板是 `patched=0` | **看错页了**：那块由另一个模板渲染，改 `detailtmpl` 永远不会生效 |
| `poi_tmpl_rule accept=false why=prefix_mismatch` | 路径白名单没覆盖这条，和卡片名无关 |
| 一条 `seen` 都没有 | 页面压根没走 `WebView.loadUrl(String)`，或 URL 不是 `file://…index.html` |

### 字段
- `name`：从 `…/runtime/map.android.baidu.<name>_<ver>/…` 里取的模板名，取不到打 `?`
- `patched=1|0`：这次有没有把 URL 换成我们打过补丁的副本
- `why`：`detail_ok` / `prefix_mismatch` / `shape` / `tail_mismatch` /
  `outside_app_data` / `no_runtime_mark` / `prepare_failed` / `template_incomplete`
- 同名模板每进程只打一条，总量封顶 12 条；`poi_tmpl_rule` 每进程一条。

### 下一步的判据（不要跳步）
只有当日志证明「用户看到的那块 = 某个 `patched=0` 的模板」时，才值得去支持那个模板
（那要扩 `detailTemplate()` 白名单 + 重新探 JS 锚点，属于**新功能**）。
在此之前，任何「再加个卡片名试试」都是白花钱。

#!/usr/bin/env bash
# 在两个 GitHub Release 和附件核验完成后按需执行。本文件仅提供公告命令，不自动发送。
sudo bash /opt/mc-announce/push.sh \
  --title "MapAdKiller 2.3.6 已更新，工具排序和天气开关更好用了" \
  --body "2.3.6 这次主要修高德首页：工具可以拖动或用上下按钮排序，打开多个工具不再只显示三个，更多工具的图标和公交角标也修好了。内容区改成天气、推荐与活动两个开关，只看天气更方便；新增强制关闭自动巡航。同步适配高德 Play，修复百度精简首页重复点击后的空白，并隐藏腾讯新人专属悬浮推广。模块、高德国内与 Play、百度和腾讯内置版统一 2.3.6。模块和内置版选一种；Play ZIP 解压后的四个 APK 要一起安装。下载和详细更新见 Release。" \
  --level info \
  --url "https://github.com/ldxm666/MapAdKiller/releases/tag/v2.3.6"

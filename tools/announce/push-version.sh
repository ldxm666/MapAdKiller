#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 发布一个版本（客户端「检查更新」从这台服务器读，不再走 GitHub）。
#
#   用法:
#     sudo ./push-version.sh --code 2301 --name 2.3.1 \
#          --notes "修复了高德开屏页被拦截的问题" \
#          --apk /var/www/html/mc/MapClean-lsp-2.3.1.apk \
#          [--apk-amap ...] [--apk-baidu ...] [--apk-tmap ...]
#
#   --apk       : LSPosed 模块版（普通用户装这个）
#   --apk-amap  : 高德免root内置版
#   --apk-baidu : 百度免root内置版
#   --apk-tmap  : 腾讯免root内置版
#   四个 apk 地址都是「下载页地址」或「APK 直链」，客户端原样跳转。
# ─────────────────────────────────────────────────────────────
set -euo pipefail

WEBROOT="${WEBROOT:-/var/www/html}"
OUT="$WEBROOT/mc/version.json"
SECRET_FILE="${SECRET_FILE:-/opt/mc-announce/secret.txt}"
HIST="/opt/mc-announce/version-history.log"

CODE=0; NAME=""; NOTES=""; APK=""; A_AMAP=""; A_BAIDU=""; A_TMAP=""

while [ $# -gt 0 ]; do
  case "$1" in
    --code)      CODE="$2";   shift 2;;
    --name)      NAME="$2";   shift 2;;
    --notes)     NOTES="$2";  shift 2;;
    --apk)       APK="$2";    shift 2;;
    --apk-amap)  A_AMAP="$2"; shift 2;;
    --apk-baidu) A_BAIDU="$2";shift 2;;
    --apk-tmap)  A_TMAP="$2"; shift 2;;
    -h|--help) sed -n '2,25p' "$0"; exit 0;;
    *) echo "未知参数: $1" >&2; exit 2;;
  esac
done

if [ "$CODE" -le 0 ] || [ -z "$NAME" ]; then
  echo "--code 和 --name 必填" >&2; exit 2
fi

esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r' \
        | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g'; }

N=$(esc "$NAME"); NT=$(esc "$NOTES"); U=$(esc "$APK")
UA=$(esc "$A_AMAP"); UB=$(esc "$A_BAIDU"); UT=$(esc "$A_TMAP")

# 签名: HMAC-SHA256 over "versionCode|versionName|apkUrl"
PAYLOAD="${CODE}|${NAME}|${APK}"
SIG=""
if [ -f "$SECRET_FILE" ] && command -v openssl >/dev/null 2>&1; then
  SIG=$(printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$(cat "$SECRET_FILE")" -hex 2>/dev/null | awk '{print $NF}')
fi

mkdir -p "$WEBROOT/mc"
TMP="$(mktemp "$WEBROOT/mc/.version.XXXXXX")"
cat > "$TMP" <<JSON
{
  "versionCode": ${CODE},
  "versionName": "${N}",
  "notes": "${NT}",
  "apkUrl": "${U}",
  "apkUrlEmbedded": {
    "com.autonavi.minimap": "${UA}",
    "com.baidu.BaiduMap": "${UB}",
    "com.tencent.map": "${UT}"
  },
  "publishedAt": $(date +%s),
  "sig": "${SIG}"
}
JSON
chmod 644 "$TMP"; mv -f "$TMP" "$OUT"

mkdir -p "$(dirname "$HIST")"
echo "$(date -Is) code=$CODE name=$NAME apk=$APK" >> "$HIST"
tail -n 100 "$HIST" > "$HIST.tmp" && mv -f "$HIST.tmp" "$HIST"

echo "已发布版本:"
echo "  versionCode = $CODE"
echo "  versionName = $NAME"
echo "  apk         = ${APK:-(空)}"
echo "  sig         = ${SIG:0:16}..."
echo "  地址        = http://<域名或IP>/mc/version.json"

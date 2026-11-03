#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 推送一条公告。可在服务器上直接跑，也可以在本地通过 ssh 调用（见 README）。
#
#   用法:
#     ./push.sh --title "2.3.1 已发布" --body "修复了高德开屏页被拦截的问题" [选项]
#
#   选项:
#     --title  <str>           标题（必填）
#     --body   <str>           正文（必填）
#     --level  info|warn|force 级别，默认 info
#     --url    <url>           「查看详情」跳转地址，可空
#     --min    <int>           只对 versionCode >= 此值 的客户端展示
#     --max    <int>           只对 versionCode <= 此值 的客户端展示
#     --expire <unix秒>        过期时间，0=不过期
#     --clear                  推一条「清空」公告（客户端不再展示任何内容）
#
#   为什么写临时文件再 mv：mv 在同一个文件系统内是原子的。
#   客户端要么读到旧公告、要么读到新公告，**绝不会读到写了一半的 JSON**。
# ─────────────────────────────────────────────────────────────
set -euo pipefail

WEBROOT="${WEBROOT:-/var/www/html}"
OUT="$WEBROOT/mc/announce.json"
SECRET_FILE="${SECRET_FILE:-/opt/mc-announce/secret.txt}"
HIST="/opt/mc-announce/history.log"

TITLE=""; BODY=""; LEVEL="info"; URL=""; MIN=0; MAX=0; EXPIRE=0; CLEAR=0

while [ $# -gt 0 ]; do
  case "$1" in
    --title)  TITLE="$2"; shift 2;;
    --body)   BODY="$2";  shift 2;;
    --level)  LEVEL="$2"; shift 2;;
    --url)    URL="$2";   shift 2;;
    --min)    MIN="$2";   shift 2;;
    --max)    MAX="$2";   shift 2;;
    --expire) EXPIRE="$2";shift 2;;
    --clear)  CLEAR=1;    shift;;
    -h|--help) sed -n '2,30p' "$0"; exit 0;;
    *) echo "未知参数: $1" >&2; exit 2;;
  esac
done

if [ "$CLEAR" -eq 0 ] && { [ -z "$TITLE" ] || [ -z "$BODY" ]; }; then
  echo "--title 和 --body 都是必填（或用 --clear 清空）" >&2; exit 2
fi

# id 用「秒级时间戳 / 100」避免超过 2^31，同时天然单调递增，客户端靠它判「新公告」
ID=$(date +%s)

if [ "$CLEAR" -eq 1 ]; then
  TITLE=""; BODY=""; LEVEL="info"; URL=""; MIN=0; MAX=0; EXPIRE=0
fi

mkdir -p "$WEBROOT/mc"

# 手工拼 JSON（不依赖 jq，2GB 机器上少装一个包）。字段已做转义。
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' | tr -d '\r' \
    | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g'
}

T=$(json_escape "$TITLE"); B=$(json_escape "$BODY"); U=$(json_escape "$URL")

# 签名: HMAC-SHA256 over "<id>|<title>|<body>"，客户端会校验，防止 HTTP 被中间人塞假公告
PAYLOAD="${ID}|${TITLE}|${BODY}"
SIG=""
if [ -f "$SECRET_FILE" ] && command -v openssl >/dev/null 2>&1; then
  SIG=$(printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$(cat "$SECRET_FILE")" -hex 2>/dev/null | awk '{print $NF}')
fi

TMP="$(mktemp "$WEBROOT/mc/.announce.XXXXXX")"
cat > "$TMP" <<JSON
{
  "id": ${ID},
  "title": "${T}",
  "body": "${B}",
  "level": "${LEVEL}",
  "url": "${U}",
  "minVersionCode": ${MIN},
  "maxVersionCode": ${MAX},
  "expireAt": ${EXPIRE},
  "sig": "${SIG}"
}
JSON

chmod 644 "$TMP"
mv -f "$TMP" "$OUT"

# 历史留档（只留最近 200 条，别把 2GB 的盘塞满）
mkdir -p "$(dirname "$HIST")"
{ echo "$(date -Is) id=$ID level=$LEVEL title=$TITLE"; } >> "$HIST"
tail -n 200 "$HIST" > "$HIST.tmp" && mv -f "$HIST.tmp" "$HIST"

echo "已推送:"
echo "  id      = $ID"
echo "  title   = $TITLE"
echo "  level   = $LEVEL"
echo "  sig     = ${SIG:0:16}..."
echo "  地址    = http://<你的域名或IP>/mc/announce.json"
echo
echo "客户端会在下次启动（或 6 小时内首次联网）时拉到。"

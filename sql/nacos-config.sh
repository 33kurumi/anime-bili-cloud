#!/usr/bin/env bash
# ── 把公共配置发布到 Nacos 配置中心 ──────────────────────────────
# 用法：bash sql/nacos-config.sh
# 依赖：curl；Nacos 运行在 127.0.0.1:8848（账号 nacos/nacos）
set -e
NACOS="http://127.0.0.1:8848/nacos/v1/cs/configs"
GROUP="DEFAULT_GROUP"
DIR="$(cd "$(dirname "$0")" && pwd)"

publish() {
  local dataId="$1" file="$2"
  local body
  body="$(cat "$file")"
  echo "发布 $dataId ($GROUP) ..."
  curl -s -X POST "$NACOS" \
    --data-urlencode "dataId=$dataId" \
    --data-urlencode "group=$GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=$body" \
    --data-urlencode "username=nacos" \
    --data-urlencode "password=nacos" \
    -w "  -> HTTP %{http_code}\n" -o /dev/null
}

publish "abc-common.yaml" "$DIR/nacos/abc-common.yaml"
publish "abc-datasource.yaml" "$DIR/nacos/abc-datasource.yaml"
echo "完成。可在 http://localhost:8848/nacos → 配置管理 查看。"

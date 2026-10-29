#!/bin/sh
# ============================================================
# 发布 Docker 环境专用的 Nacos 共享配置（一次性作业，由 compose 的 nacos-config-init 执行）
# 为什么要它：每个服务的 bootstrap.yml 写死 127.0.0.1:8848 且从 Nacos 拉
#   abc-common.yaml / abc-datasource.yaml，而这两个配置文件里的 host 是 127.0.0.1。
#   容器里 127.0.0.1 指向自己，必须换成服务名（redis / mysql）才能连通。
# 做法：nacos 的健康检查通过后，POST /nacos/v1/auth/login 拿 accessToken，
#       再逐个 POST /nacos/v1/cs/configs 覆盖发布（幂等，重复执行无副作用）。
# 失败即非 0 退出 —— compose 里依赖它的服务用 service_completed_successfully 卡住。
# ============================================================
set -e

NACOS_ADDR="${NACOS_ADDR:-http://nacos:8848}"
CONF_DIR="${CONF_DIR:-/config}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"

echo "[init-nacos] waiting for ${NACOS_ADDR} ..."
i=0
TOKEN=""
while [ $i -lt 60 ]; do
  i=$((i+1))
  RESP=$(curl -s -m 5 -X POST "${NACOS_ADDR}/nacos/v1/auth/login" \
        -d "username=${NACOS_USER}&password=${NACOS_PASSWORD}" || true)
  TOKEN=$(printf '%s' "$RESP" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -n "$TOKEN" ]; then
    echo "[init-nacos] login ok (attempt $i)"
    break
  fi
  echo "[init-nacos] not ready yet ($i): $(printf '%s' "$RESP" | head -c 120)"
  sleep 2
done

if [ -z "$TOKEN" ]; then
  echo "[init-nacos] ERROR: cannot login to nacos, abort" >&2
  exit 1
fi

publish() {
  DATA_ID="$1"
  FILE="$2"
  CODE=$(curl -s -m 10 -o /tmp/nacos_resp.txt -w '%{http_code}' -X POST "${NACOS_ADDR}/nacos/v1/cs/configs" \
      --data-urlencode "dataId=${DATA_ID}" \
      --data-urlencode "group=DEFAULT_GROUP" \
      --data-urlencode "type=yaml" \
      --data-urlencode "content@${CONF_DIR}/${FILE}" \
      --data-urlencode "accessToken=${TOKEN}" || true)
  BODY=$(cat /tmp/nacos_resp.txt 2>/dev/null || echo "")
  echo "[init-nacos] publish ${DATA_ID} -> HTTP ${CODE} body=${BODY}"
  if [ "$BODY" != "true" ]; then
    echo "[init-nacos] ERROR: publish ${DATA_ID} failed" >&2
    exit 1
  fi
}

publish abc-common.yaml      abc-common.yaml
publish abc-datasource.yaml  abc-datasource.yaml

echo "[init-nacos] all shared configs published."

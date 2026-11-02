#!/usr/bin/env bash
set -euo pipefail

# Требуются запущенные PostgreSQL, Config Server, Eureka и commerce-сервисы с Gateway.
gateway=${GATEWAY_URL:-http://localhost:8080}
config=${CONFIG_URL:-http://localhost:8888}
discovery=${DISCOVERY_URL:-http://localhost:8761}

request() {
  local method=$1 route=$2 expected=$3 auth=${4:-} payload=${5:-}
  local args=(-sS --max-time 15 -X "$method" "$gateway$route")
  if [[ -n "$auth" ]]; then args+=(-u "$auth"); fi
  if [[ -n "$payload" ]]; then args+=(-H 'Content-Type: application/json' -d "$payload"); fi
  local response
  response=$(curl "${args[@]}" -w $'\n%{http_code}')
  local status=${response##*$'\n'}
  body=${response%$'\n'*}
  if [[ "$status" != "$expected" ]]; then
    printf '%s %s: expected %s, got %s: %s\n' "$method" "$route" "$expected" "$status" "$body" >&2
    exit 1
  fi
}

for service in collector aggregator analyzer product-service inventory-service order-service api-gateway; do
  curl -fsS "$config/$service/default" | jq -e '.propertySources | length >= 2' >/dev/null
done
curl -fsS "$discovery/eureka/apps" -H 'Accept: application/json' | jq -e '
  [.applications.application[].name] as $names |
  ["PRODUCT-SERVICE","INVENTORY-SERVICE","ORDER-SERVICE","API-GATEWAY"] |
  all(. as $name | $names | index($name) != null)' >/dev/null

request GET /api/products 200
request POST /api/orders 401 '' '{}'
request PATCH /api/products/1 403 ivan:ivan '{}'
request GET /api/orders 403 ivan:ivan
request GET /api/orders 200 anna:anna
request GET /api/unknown 403 anna:anna
request GET /api/orders/1 401 ivan:wrong

request POST /api/categories 201 anna:anna '{"name":"Sprint22 smoke","description":"Runtime integration"}'
category=$(jq -r .id <<< "$body")
ids=()
for label in Stocked Empty MissingInventory Inactive; do
  request POST /api/products 201 anna:anna \
    "{\"name\":\"Smoke $label\",\"price\":199.90,\"categoryId\":$category}"
  ids+=("$(jq -r .id <<< "$body")")
done
stocked=${ids[0]}; empty=${ids[1]}; missing=${ids[2]}; inactive=${ids[3]}
request POST /api/inventory 201 anna:anna "{\"productId\":$stocked,\"quantity\":20}"
request POST /api/inventory 201 anna:anna "{\"productId\":$empty,\"quantity\":0}"
request PATCH "/api/products/$inactive" 200 anna:anna '{"active":false}'

orderPayload="{\"customerName\":\"Smoke Buyer\",\"customerEmail\":\"smoke22@example.com\",\"items\":[{\"productId\":$stocked,\"quantity\":2,\"price\":0.01,\"productName\":\"Untrusted\"},{\"productId\":$stocked,\"quantity\":3}]}"
request POST /api/orders 201 ivan:ivan "$orderPayload"
jq -e '.status == "CONFIRMED" and .totalPrice == 999.50 and (.items | length) == 2 and
  all(.items[]; .productName == "Smoke Stocked" and .price == 199.90)' <<< "$body" >/dev/null
order=$(jq -r .id <<< "$body")
request GET "/api/inventory/$stocked" 200
jq -e '.reservedQuantity == 5 and .availableQuantity == 15' <<< "$body" >/dev/null
request PATCH "/api/products/$stocked" 200 anna:anna '{"name":"Updated catalog name","price":299.90}'
request GET "/api/orders/$order" 200 ivan:ivan
jq -e '.totalPrice == 999.50 and .items[0].productName == "Smoke Stocked"' <<< "$body" >/dev/null
request GET '/api/orders/by-email?email=smoke22@example.com' 200 ivan:ivan
jq -e --argjson id "$order" 'any(.[]; .id == $id)' <<< "$body" >/dev/null

for rejected in "$empty" "$missing" "$inactive" 999999999; do
  request POST /api/orders 422 ivan:ivan \
    "{\"customerName\":\"Smoke Buyer\",\"customerEmail\":\"smoke22@example.com\",\"items\":[{\"productId\":$stocked,\"quantity\":1},{\"productId\":$rejected,\"quantity\":1}]}"
  jq -e '.status == 422 and (.message | length > 0)' <<< "$body" >/dev/null
done
request GET "/api/inventory/$stocked" 200
jq -e '.reservedQuantity == 5' <<< "$body" >/dev/null
request POST /api/inventory/release 400 anna:anna "{\"productId\":$stocked,\"quantity\":6}"
request POST /api/inventory/release 400 anna:anna "{\"productId\":$stocked,\"quantity\":0}"

headers=$(curl -sS -D - -o /dev/null -X OPTIONS "$gateway/api/orders" \
  -H 'Origin: http://localhost:8443' -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type')
[[ "$headers" == *'200'* && "$headers" == *'http://localhost:8443'* ]]
for attempt in {1..12}; do request GET '/api/products?smoke-loadbalance' 200; done
printf 'PASS: config, discovery, roles, orders, snapshots, duplicate products, compensation, 404/409, CORS\n'
printf 'Order=%s; StockedProduct=%s; EmptyProduct=%s\n' "$order" "$stocked" "$empty"

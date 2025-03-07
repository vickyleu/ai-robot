#!/bin/bash

# 设置API服务地址
HOST=${API_HOST:-"192.168.0.7"}
PORT=${API_PORT:-"8080"}
API_URL="http://${HOST}:${PORT}/completion"

# 设置Charles代理
#PROXY_HOST=${PROXY_HOST:-"192.168.0.7"}
#PROXY_PORT=${PROXY_PORT:-"8888"}

# 测试数据
TEST_DATA='{"query":"Hello","inputs":{},"user":"test_user","response_mode":"blocking"}'

echo "正在测试API服务..."
echo "API地址: ${API_URL}"
#echo "使用代理: ${PROXY_HOST}:${PROXY_PORT}"

# 发送测试请求
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST \
  -H "Content-Type: application/json" \
  -d "$TEST_DATA" \
  "$API_URL")

# 提取响应状态码和内容
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
CONTENT=$(echo "$RESPONSE" | sed \$d)

# 检查HTTP状态码
if [ "$HTTP_CODE" -eq 200 ]; then
    echo "✅ API服务正常运行"
    echo "响应内容:"
    echo "$CONTENT" | jq .
    
    # 验证JSON响应格式
    if echo "$CONTENT" | jq . >/dev/null 2>&1; then
        # 解析响应状态码
        CODE=$(echo "$CONTENT" | jq -r '.code')
        MSG=$(echo "$CONTENT" | jq -r '.msg')
        
        if [ "$CODE" -eq 200 ]; then
            echo "✅ 请求成功"
            echo "✅ 响应格式正确 (有效的JSON)"
            exit 0
        else
            echo "❌ 请求失败: $MSG"
            echo "✅ 响应格式正确 (有效的JSON)"
            exit 1
        fi
    else
        echo "❌ 响应格式错误 (无效的JSON)"
        exit 1
    fi
else
    echo "❌ API服务测试失败"
    echo "HTTP状态码: $HTTP_CODE"
    echo "错误信息:"
    echo "$CONTENT"
    exit 1
fi
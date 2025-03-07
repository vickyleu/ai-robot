#!/bin/bash

# 设置gRPC服务地址
HOST=${GRPC_HOST:-"192.168.0.7"}
PORT=${GRPC_PORT:-"50051"}

## 设置代理服务器
#PROXY_HOST=${PROXY_HOST:-"192.168.0.7"}
#PROXY_PORT=${PROXY_PORT:-"8888"}

# 测试数据
TEST_DATA='{"query":"Hello","inputs":{},"user":"test_user","response_mode":"blocking"}'

echo "正在测试gRPC服务..."
echo "服务地址: ${HOST}:${PORT}"
#echo "使用代理: ${PROXY_HOST}:${PROXY_PORT}"

# 使用grpcurl发送测试请求
if ! command -v grpcurl &> /dev/null; then
    echo "❌ 请先安装grpcurl工具"
    echo "可以使用以下命令安装："
    echo "brew install grpcurl  # MacOS"
    exit 1
fi

# 发送请求并记录开始时间
start_time=$(date +%s.%N)

# 发送gRPC请求
# //http_proxy="http://${PROXY_HOST}:${PROXY_PORT}" https_proxy="http://${PROXY_HOST}:${PROXY_PORT}"
RESPONSE=$(grpcurl -plaintext \
    -d "$TEST_DATA" \
    -import-path ./api/proto \
    -proto completion.proto \
    ${HOST}:${PORT} \
    completion.CompletionService/GetCompletion)

# 获取最终耗时
end_time=$(date +%s.%N)
total_time=$(echo "$end_time - $start_time" | bc)

echo "请求完成！总耗时: ${total_time}秒"

# 显示响应内容
echo "响应内容:"
if ! echo "$RESPONSE" | jq . >/dev/null 2>&1; then
    echo "$RESPONSE"
    echo "❌ 响应格式错误 (无效的JSON)"
    exit 1
fi

# 使用jq美化输出并添加颜色
echo "$RESPONSE" | jq .

# 解析响应状态码
CODE=$(echo "$RESPONSE" | jq -r '.code // empty')
MSG=$(echo "$RESPONSE" | jq -r '.msg // empty')

if [ -z "$CODE" ]; then
    echo "❌ 响应缺少必要字段: 'code'"
    exit 1
fi

if [ -z "$MSG" ]; then
    echo "❌ 响应缺少必要字段: 'msg'"
    exit 1
fi

if [ "$CODE" -eq 200 ]; then
    echo "✅ 请求成功"
    echo "✅ 响应格式正确 (有效的JSON)"
    exit 0
else
    echo "❌ 请求失败: $MSG"
    echo "✅ 响应格式正确 (有效的JSON)"
    exit 1
fi
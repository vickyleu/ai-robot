#!/bin/bash

# 设置API服务地址
HOST=${API_HOST:-"192.168.0.7"}
PORT=${API_PORT:-"8080"}
API_URL="http://${HOST}:${PORT}/completion"

# 设置Charles代理
#PROXY_HOST=${PROXY_HOST:-"192.168.0.7"}
#PROXY_PORT=${PROXY_PORT:-"8888"}

# 测试数据
TEST_DATA='{"query":"你好","inputs":{},"user":"test_user","response_mode":"blocking"}'

echo "正在测试API服务..."
echo "API地址: ${API_URL}"
#echo "使用代理: ${PROXY_HOST}:${PROXY_PORT}"

# 创建临时文件存储curl输出
temp_file=$(mktemp)

# 发送请求并记录开始时间
start_time=$(date +%s.%N)

# 发送请求到后台并获取PID
# //-x "${PROXY_HOST}:${PROXY_PORT}" \
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "$TEST_DATA" \
  "$API_URL" > "$temp_file" &
curl_pid=$!

# 进度条字符
spinner=("⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏")
spinner_idx=0

# 显示进度条直到curl完成
while kill -0 $curl_pid 2>/dev/null; do
    current_time=$(date +%s.%N)
    elapsed=$(echo "$current_time - $start_time" | bc)
    printf "\r${spinner[$spinner_idx]} 请求进行中... %.2f秒" $elapsed
    spinner_idx=$(((spinner_idx + 1) % 10))
    sleep 0.1
done

# 获取最终耗时
end_time=$(date +%s.%N)
total_time=$(echo "$end_time - $start_time" | bc)

# 清除进度条行
printf "\r"

# 显示响应结果和总耗时
echo "请求完成！总耗时: ${total_time}秒"

# 读取响应内容
CONTENT=$(cat "$temp_file")

# 清理临时文件
rm -f "$temp_file"

# 显示响应内容
echo "响应内容:"
echo "$CONTENT"  | jq .

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
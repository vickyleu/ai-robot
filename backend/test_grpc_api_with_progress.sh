#!/bin/bash

# 设置gRPC服务地址
HOST=${GRPC_HOST:-"192.168.0.7"}
PORT=${GRPC_PORT:-"50051"}

## 设置代理服务器
#PROXY_HOST=${PROXY_HOST:-"192.168.0.7"}
#PROXY_PORT=${PROXY_PORT:-"8888"}

# 测试数据
TEST_DATA='{"query":"Hello","inputs":{},"user":"test_user","response_mode":"blocking"}'

# 检查依赖工具
check_dependencies() {
    if ! command -v grpcurl &> /dev/null; then
        echo "❌ 请先安装grpcurl工具"
        echo "可以使用以下命令安装："
        echo "brew install grpcurl  # MacOS"
        exit 1
    fi
}

echo "正在测试gRPC服务..."
echo "服务地址: ${HOST}:${PORT}"
#echo "使用代理: ${PROXY_HOST}:${PROXY_PORT}"

# 检查依赖
check_dependencies

# 创建临时文件存储响应
response_file=$(mktemp)

# 记录开始时间
start_time=$(date +%s.%N)

# 在后台发送gRPC请求并保存响应
#http_proxy="http://${PROXY_HOST}:${PROXY_PORT}" https_proxy="http://${PROXY_HOST}:${PROXY_PORT}"
grpcurl -plaintext \
    -d "$TEST_DATA" \
    -import-path ./api/proto \
    -proto completion.proto \
    ${HOST}:${PORT} \
    completion.CompletionService/GetCompletion > "$response_file" 2>&1 &

# 获取后台进程ID
BG_PID=$!

# 进度条字符
spinner=("⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏")
spinner_idx=0

# 显示进度条直到请求完成
while kill -0 $BG_PID 2>/dev/null; do
    current_time=$(date +%s.%N)
    elapsed=$(echo "$current_time - $start_time" | bc)
    printf "\r${spinner[$spinner_idx]} 请求进行中... %.2f秒" $elapsed
    spinner_idx=$(((spinner_idx + 1) % 10))
    sleep 0.1
done

# 等待后台进程完成
wait $BG_PID
EXIT_CODE=$?

# 获取最终耗时
end_time=$(date +%s.%N)
total_time=$(echo "$end_time - $start_time" | bc)

# 清除进度条行
printf "\r%$(tput cols)s\r"

if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ 请求成功！总耗时: ${total_time}秒"
    echo "响应内容:"
    if cat "$response_file" | jq . >/dev/null 2>&1; then
        # 使用jq美化输出并添加颜色
        cat "$response_file" | jq .

        # 解析响应状态码
        CODE=$(cat "$response_file" | jq -r '.code')
        MSG=$(cat "$response_file" | jq -r '.msg')

        if [ "$CODE" -eq 200 ]; then
            echo "✅ 请求成功"
            echo "✅ 响应格式正确 (有效的JSON)"
            rm -f "$response_file"
            exit 0
        else
            echo "❌ 请求失败: $MSG"
            echo "✅ 响应格式正确 (有效的JSON)"
            rm -f "$response_file"
            exit 1
        fi
    else
        echo "❌ 响应格式错误 (无效的JSON)"
        echo "错误内容:"
        echo "$(cat "$response_file")"
        rm -f "$response_file"
        exit 1
    fi
else
    echo "❌ 请求失败"
    echo "错误信息:"
    cat "$response_file"
    rm -f "$response_file"
    exit 1
fi
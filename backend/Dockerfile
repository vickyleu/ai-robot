# 构建阶段
FROM golang:1.22-alpine AS builder

# 设置工作目录
WORKDIR /app

# 安装必要的构建工具
RUN apk add --no-cache git

# 复制go mod和sum文件
COPY go.mod go.sum ./

# 下载依赖
RUN go mod download

# 复制源代码
COPY . .

# 构建应用
RUN CGO_ENABLED=0 GOOS=linux go build -o ai-service ./cmd/server

# 运行阶段
FROM alpine:latest

# 安装必要的运行时依赖
RUN apk add --no-cache ca-certificates tzdata

# 设置时区
ENV TZ=Asia/Shanghai

# 创建非root用户
RUN adduser -D -g '' appuser

# 设置工作目录
WORKDIR /app

# 从构建阶段复制二进制文件
COPY --from=builder /app/ai-service .

# 使用非root用户运行
USER appuser

# 暴露端口
EXPOSE 8080 50051

# 运行应用
CMD ["./ai-service"]
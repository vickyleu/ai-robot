# 使用官方的 ARM32v7 Debian 镜像作为基础镜像
FROM arm32v7/debian:bullseye

# 设置非交互模式，避免安装时的提示
ENV DEBIAN_FRONTEND=noninteractive

# 更新软件包索引并安装交叉编译工具和其他常用工具
RUN apt-get update && apt-get install -y \
    build-essential \
    gcc \
    g++ \
    make \
    cmake \
    pkg-config \
    wget \
    curl \
    git \
    bash-completion \
    && apt-get clean

# 设置默认工作目录
WORKDIR /build

# 启动时默认执行 bash
CMD ["bash"]

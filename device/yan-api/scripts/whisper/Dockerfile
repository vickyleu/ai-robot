# Dockerfile
# 使用树莓派3兼容的基础镜像（ARMv7架构）
# 修改后的 Dockerfile
# 使用阿里云镜像源加速
FROM arm32v7/debian:bullseye AS builder

# 设置编译参数
ENV CFLAGS="-O3 -mcpu=cortex-a53 -mfpu=neon-vfpv4 -mfloat-abi=hard -pipe"
ENV CXXFLAGS="${CFLAGS}"

# 使用清华源加速包下载
RUN sed -i 's/deb.debian.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apt/sources.list && \
    sed -i 's/security.debian.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apt/sources.list

RUN apt-get update && apt-get install -y \
    git cmake build-essential \
    libopenblas-dev python3-pip wget curl \
    && rm -rf /var/lib/apt/lists/*

# 克隆仓库并锁定到稳定版本
WORKDIR /app
RUN git clone https://github.com/ggml-org/whisper.cpp.git && \
    cd whisper.cpp

# 进入正确目录编译
WORKDIR /app/whisper.cpp
RUN make WHISPER_OPENBLAS=1 \
    WHISPER_NO_AVX=1 \
    WHISPER_NO_AVX2=1 \
    WHISPER_NO_GPU=1 \
    CFLAGS="-mfp16-format=ieee -marm" \
    CXXFLAGS="-mfp16-format=ieee -marm"

# 下载默认模型（tiny.en量化版）
RUN ./models/download-ggml-model.sh tiny.en

# 构建最终镜像（极简运行环境）
FROM arm32v7/debian:bullseye-slim

# 安装运行时依赖
RUN apt-get update && apt-get install -y \
    libopenblas-base libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# 拷贝编译结果
COPY --from=builder /app/whisper.cpp /app/whisper.cpp

# 设置工作目录
WORKDIR /app/whisper.cpp
RUN ls -la
# 定义默认命令（运行测试）
CMD ./build/bin/whisper-cli -m models/ggml-tiny.en.bin -f samples/jfk.wav && tail -f /dev/null
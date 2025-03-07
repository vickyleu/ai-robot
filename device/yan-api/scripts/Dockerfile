FROM balenalib/raspberrypi3-python:sid

# 强制使用IPv4，更新APT源并安装必要组件
RUN echo 'Acquire::ForceIPv4 "true";' > /etc/apt/apt.conf.d/99force-ipv4 && \
    apt-get update && \
    apt-get install -y \
        xfce4  xfce4-goodies tightvncserver dbus-x11 xfonts-base \
        xauth openssh-server xserver-xorg-core \
        xserver-xorg-video-all xserver-xorg-input-all libgl1-mesa-dri  libglu1-mesa \
        gvfs gdb wget curl git vim  build-essential llvm xz-utils python3-openssl libssl-dev zlib1g-dev  libncurses5-dev   libncursesw5-dev libreadline-dev \
        libsqlite3-dev  libffi-dev libbz2-dev liblzma-dev libc6-dev tk-dev make sudo && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    ssh-keygen -A

# 下载并编译安装 Python 3.5.10
RUN wget https://www.python.org/ftp/python/3.5.10/Python-3.5.10.tgz && \
    tar -xvf Python-3.5.10.tgz && \
    cd Python-3.5.10 && \
    export LIBS="-lm" && \
    ./configure --enable-optimizations --with-pymalloc --enable-shared &&  \
    make -j$(nproc) && \
    make altinstall && \
    cd .. && \
    rm -rf Python-3.5.10 Python-3.5.10.tgz && \
    update-alternatives --install /usr/bin/python3 python3 /usr/local/bin/python3.5 1 && \
    update-alternatives --set python3 /usr/local/bin/python3.5

# 创建默认pi用户
RUN useradd -m -G sudo -s /bin/bash pi && \
    echo "pi:raspberry" | chpasswd && \
    echo "pi ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

USER pi
WORKDIR /home/pi
RUN mkdir -p /home/pi

# 配置VNC服务器
USER root
WORKDIR /root
RUN echo "root:raspberry" | chpasswd

RUN mkdir -p /root/.vnc && \
    touch /root/.Xauthority && \
    echo -e "#!/bin/bash\nxrdb \$HOME/.Xresources\ndbus-launch --exit-with-session startxfce4 &" > /root/.vnc/xstartup && \
    chmod +x /root/.vnc/xstartup  && \
    echo "raspberry" | vncpasswd -f > /root/.vnc/passwd && \
    chmod 600 /root/.vnc/passwd

# 配置SSH服务
RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/' /etc/ssh/sshd_config && \
    sed -i 's/#PasswordAuthentication yes/PasswordAuthentication yes/' /etc/ssh/sshd_config

# 暴露端口
EXPOSE 5901 22

# 启动服务（直接以root用户运行）
CMD ["sh", "-c", "service ssh start && vncserver :1 -geometry 1280x720 -depth 24 && tail -f /dev/null"]
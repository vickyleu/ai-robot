#!/bin/bash
set -e

case "$1" in
    configure)
        # 检查模型文件是否存在，如果不存在则尝试从备份恢复
        if [ ! -d "/usr/local/share/yanshee-model" ] || [ -z "$(ls -A /usr/local/share/yanshee-model 2>/dev/null)" ]; then
            if [ -d "/var/backups/yanshee/model" ] && [ ! -z "$(ls -A /var/backups/yanshee/model 2>/dev/null)" ]; then
                mkdir -p /usr/local/share/yanshee-model
                cp -r /var/backups/yanshee/model/* /usr/local/share/yanshee-model/
                chmod 755 /usr/local/share/yanshee-model/*
                
                # 检查是否有需要解压的zip文件
                if [ -f "/usr/local/share/yanshee-model/vosk-model-small-cn-0.22.zip" ]; then
                    cd /usr/local/share/yanshee-model/
                    unzip vosk-model-small-cn-0.22.zip
                    rm vosk-model-small-cn-0.22.zip
                fi
            fi
        fi

        # 检查动态库是否存在，如果不存在则尝试从备份恢复
        if [ -d "/var/backups/yanshee/lib" ] && [ ! -z "$(ls -A /var/backups/yanshee/lib 2>/dev/null)" ]; then
            # 复制备份的动态库
            cp -r /var/backups/yanshee/lib/* /usr/local/lib/
            chmod 755 /usr/local/lib/*.so*
        fi

        # 更新动态库缓存
        ldconfig
    ;;
esac

exit 0
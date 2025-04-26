#!/bin/bash
set -e

case "$1" in
    configure)
        # 解压模型文件
        if [ -f "/usr/local/share/yanshee-model/vosk-model-small-cn-0.22.zip" ]; then
            cd /usr/local/share/yanshee-model/
            unzip -o vosk-model-small-cn-0.22.zip
            rm vosk-model-small-cn-0.22.zip
        fi
        
        # 确保文件权限正确
        for f in /usr/local/share/yanshee-model/*; do
            if [ -f "$f" ]; then
                chmod 755 "$f"
            fi
        done
        for f in /usr/local/lib/*.so*; do
            if [ -f "$f" ]; then
                chmod 755 "$f"
            fi
        done
        
        # 更新动态库缓存
        ldconfig
    ;;
esac

exit 0
#!/bin/bash
set -e

case "$1" in
    configure)
        # 检查模型文件是否存在，如果不存在则尝试从备份恢复
        if [ ! -d "/usr/local/share/yanshee-model" ] || [ -z "$(ls -A /usr/local/share/yanshee-model 2>/dev/null)" ]; then
            if [ -d "/var/backups/yanshee/model" ] && [ ! -z "$(ls -A /var/backups/yanshee/model 2>/dev/null)" ]; then
                mkdir -p /usr/local/share/yanshee-model
                cp -r /var/backups/yanshee/model/* /usr/local/share/yanshee-model/
                # 仅对存在且为普通文件的目标执行chmod
                for f in /usr/local/share/yanshee-model/*; do
                    if [ -f "$f" ]; then
                        chmod 755 "$f"
                    fi
                done
                
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
            # 仅对存在且为普通文件的目标执行chmod
            for f in /usr/local/lib/*.so*; do
                if [ -f "$f" ]; then
                    chmod 755 "$f"
                fi
            done
        fi

        # 更新动态库缓存
        ldconfig
        # 检查主程序符号链接是否存在且类型正确，否则重新创建
        if [ ! -L "/usr/bin/yanshee" ] || [ "$(readlink -- "/usr/bin/yanshee")" != "/usr/local/bin/yanshee/yanshee" ]; then
            if [ -e "/usr/bin/yanshee" ] && [ ! -L "/usr/bin/yanshee" ]; then
                rm -f "/usr/bin/yanshee"
            fi
            ln -sf "/usr/local/bin/yanshee/yanshee" "/usr/bin/yanshee"
            chmod 755 "/usr/bin/yanshee"
        fi
    ;;
esac

exit 0
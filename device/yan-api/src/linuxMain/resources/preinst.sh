#!/bin/bash
set -e

# 在卸载前备份重要文件
if [ "$1" = "upgrade" ] || [ "$1" = "remove" ]; then
    # 确保备份目录存在
    mkdir -p /var/backups/yanshee

    # 如果模型文件存在，备份它们
    if [ -d "/usr/local/share/yanshee-model" ]; then
        mkdir -p /var/backups/yanshee/model
        cp -r /usr/local/share/yanshee-model/* /var/backups/yanshee/model/
    fi

    # 如果动态库存在，备份它们
    if [ -d "/usr/local/lib" ]; then
        mkdir -p /var/backups/yanshee/lib
        cp -r /usr/local/lib/*.so* /var/backups/yanshee/lib/ 2>/dev/null || true
    fi
fi

exit 0
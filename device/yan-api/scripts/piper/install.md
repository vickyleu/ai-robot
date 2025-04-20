## 构建armv7l的onnxruntime静态库
git clone https://github.com/vickyleu/onnxruntime-build.git
cd onnxruntime-build
export ONNXRUNTIME_VERSION=1.16.3
export CMAKE_OPTIONS=" -U__STDC_VERSION__ -D__STDC_VERSION__=201112L -DCMAKE_TOOLCHAIN_FILE=$(pwd)/arm-linux-gnueabihf.toolchain.cmake \
-Donnxruntime_CROSS_COMPILING=ON -Donnxruntime_BUILD_SHARED_LIBS=OFF   -Donnxruntime_BUILD_UNIT_TESTS=OFF  "

sh ./build-static_lib.sh
# 如果构建失败的话,执行reset可以恢复构建状态,不需要重新下载源码
git reset --hard HEAD
# 上传libonnxruntime.a到自己的预编译仓库地址

# piper源码修改下载路径

## 编译piper静态库


# 下载piper源码
git clone https://github.com/vickyleu/piper.git
cd piper
mkdir build_pi && cd build_pi
mkdir build
# 编译piper
cmake ..  -DCMAKE_TOOLCHAIN_FILE=$(pwd)/../onnxruntime-build/arm-linux-gnueabihf.toolchain.cmake -DCMAKE_INSTALL_PREFIX=./build -DBUILD_SHARED_LIBS=OFF
make -j8
make install

# 合并piper和onnxruntime等静态库, 只保留一个静态库





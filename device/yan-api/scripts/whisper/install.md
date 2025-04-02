export CC=armv7-unknown-linux-gnueabihf-gcc
export CXX=armv7-unknown-linux-gnueabihf-g++
export AR=armv7-unknown-linux-gnueabihf-ar
export AS=armv7-unknown-linux-gnueabihf-as
export LD=armv7-unknown-linux-gnueabihf-ld
export RANLIB=armv7-unknown-linux-gnueabihf-ranlib
export STRIP=armv7-unknown-linux-gnueabihf-strip
export NM=armv7-unknown-linux-gnueabihf-nm
export OBJCOPY=armv7-unknown-linux-gnueabihf-objcopy
export OBJDUMP=armv7-unknown-linux-gnueabihf-objdump
export READELF=armv7-unknown-linux-gnueabihf-readelf
export SYSROOT=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot
export PATH=$PATH:/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin
export CFLAGS="-march=armv7-a -mfpu=neon -mfloat-abi=hard -fPIC -I${SYSROOT}/usr/include"
export CXXFLAGS="${CFLAGS} -std=c++17 -I${SYSROOT}/../include/c++/13.3.0 -I${SYSROOT}/../include/c++/13.3.0/armv7-unknown-linux-gnueabihf"
export LDFLAGS="--sysroot=${SYSROOT} -L${SYSROOT}/usr/lib -L${SYSROOT}/../lib -L${SYSROOT}/../include/c++/13.3.0/armv7-unknown-linux-gnueabihf"
export OLD_LDFLAGS="${LDFLAGS}"
export LDFLAGS="${LDFLAGS} -static-libstdc++"
export CXXFLAGS="${CXXFLAGS} -I/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/include"
export LDFLAGS="${LDFLAGS} -L/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib"
export ICU_ROOT=/Volumes/Extra/Github/ktor/library/icu-release-77-1/build
export CXXFLAGS="${CXXFLAGS} -O3"
export CMAKE_OSX_ARCHITECTURES=""
export CMAKE_OSX_SYSROOT=""

# 官方的写法
cmake -B build -DGGML_BLAS=1
cmake --build build -j --config Release

# 我的写法
cmake .. \
-DCMAKE_SYSTEM_NAME=Linux \
-DCMAKE_SYSTEM_PROCESSOR=arm \
-DCMAKE_INSTALL_PREFIX=./build \
-DCMAKE_SYSROOT=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot \
-DCMAKE_C_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-gcc \
-DCMAKE_CXX_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-g++ \
-DCMAKE_AR=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-ar \
-DCMAKE_RANLIB=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-ranlib \
-DCMAKE_NM=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-nm \
-DCMAKE_OBJCOPY=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-objcopy \
-DCMAKE_OBJDUMP=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-objdump \
-DCMAKE_STRIP=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-strip \
-DCMAKE_C_FLAGS="-march=armv7-a -mfpu=neon -mfp16-format=ieee -mfloat-abi=hard -fPIC -I/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot/usr/include" \
-DCMAKE_CXX_FLAGS="-march=armv7-a -mfpu=neon -mfp16-format=ieee -mfloat-abi=hard -fPIC -std=c++17 -I/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot/usr/include -I/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/include/c++/13.3.0 -I/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/include/c++/13.3.0/armv7-unknown-linux-gnueabihf -I/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/include -I/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/include -O3" \
-DCMAKE_EXE_LINKER_FLAGS="--sysroot=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot/usr/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/include/c++/13.3.0/armv7-unknown-linux-gnueabihf -L/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib -L/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/lib -static-libstdc++" \
-DCMAKE_SHARED_LINKER_FLAGS="--sysroot=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot/usr/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/include/c++/13.3.0/armv7-unknown-linux-gnueabihf -L/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib -L/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/lib -static-libstdc++" \
-DCMAKE_MODULE_LINKER_FLAGS="--sysroot=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot/usr/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/lib -L/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/include/c++/13.3.0/armv7-unknown-linux-gnueabihf -L/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib -L/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/lib -static-libstdc++" \
-DCMAKE_FIND_ROOT_PATH="/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot;/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf;/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build;/Volumes/Extra/Github/ktor/library/icu-release-77-1/build;/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/OpenBLAS/install" \
-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY \
-DGGML_BLAS_VENDOR=OpenBLAS \
-DBUILD_SHARED_LIBS=OFF \
-DWHISPER_BUILD_EXAMPLES=ON \
-DWHISPER_BUILD_TESTS=OFF \
-DCMAKE_OSX_ARCHITECTURES="" \
-DCMAKE_OSX_SYSROOT="" \
-DCMAKE_OSX_DEPLOYMENT_TARGET=""


make -j8 
make install



cd build & mkdir merge & cd merge
armv7-unknown-linux-gnueabihf-ar x ../libggml-base.a   # 提取 libggml-base.a 中所有 .o
armv7-unknown-linux-gnueabihf-ar x ../libggml-cpu.a    # 提取 libggml-cpu.a
armv7-unknown-linux-gnueabihf-ar x ../libggml.a        # 提取 libggml.a  
armv7-unknown-linux-gnueabihf-ar x ../libwhisper.a     # 提取 libwhisper.a

armv7-unknown-linux-gnueabihf-ar rcs libwhisper.a *.o
armv7-unknown-linux-gnueabihf-ranlib libwhisper.a
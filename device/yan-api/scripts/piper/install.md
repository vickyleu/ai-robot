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

cmake .. \
-DCMAKE_TOOLCHAIN_FILE=../fake_linux.cmake \
-DCMAKE_INSTALL_PREFIX=./build \
-DBUILD_SHARED_LIBS=OFF
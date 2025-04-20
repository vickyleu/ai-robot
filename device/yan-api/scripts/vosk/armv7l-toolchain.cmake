# 设置目标系统名称和处理器架构
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR armv7l)
# 设置运行时的rpath搜索路径
set(CMAKE_INSTALL_RPATH "/usr/lib/arm-linux-gnueabihf")
# 防止 rpath 被移除
set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
# 设置 C 和 C++ 编译器为 gcc 和 g++
set(CMAKE_C_COMPILER "armv7-linux-gnueabihf-gcc")
set(CMAKE_CXX_COMPILER "armv7-linux-gnueabihf-g++")
set(CMAKE_CROSSCOMPILING TRUE)
set(GNU_VAR1 "armv7-unknown-linux-gnueabihf")
set(GNU_VAR2 "armv7-linux-gnueabihf")

file(GLOB_RECURSE COMPILER_PATHS
        "/usr/local/Cellar/${GNU_VAR1}/*/bin/${GNU_VAR2}-ld"
)
list(GET COMPILER_PATHS 0 GCC_C_LD)

# 如果未找到编译器，则提示错误
if (NOT GCC_C_LD)
    message(FATAL_ERROR "未找到 ${GNU_VAR2}-ld 链接器。请确保其已安装并在系统 PATH 中。\
        或执行brew install messense/macos-cross-toolchains/${GNU_VAR1} ")
endif ()
get_filename_component(GCC_C_LD_DIR1 ${GCC_C_LD} DIRECTORY)
get_filename_component(GCC_C_LD_DIR2 ${GCC_C_LD_DIR1} DIRECTORY)

# 定义工具链路径
set(TOOLCHAIN_PATH "${GCC_C_LD_DIR2}/toolchain")
set(SYSROOT "${TOOLCHAIN_PATH}/${GNU_VAR1}/sysroot")


# 设置交叉编译目标
set(CMAKE_C_COMPILER_TARGET "${GNU_VAR1}")
set(CMAKE_CXX_COMPILER_TARGET "${GNU_VAR1}")

if (NOT SYSROOT)
    message(FATAL_ERROR "未找到 sysroot。请确保其已安装并在系统 PATH 中。")
endif ()



set(SYSLINKER "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-ld")

# 如果未找到 sysroot，则提示错误
if (NOT SYSLINKER)
    message(FATAL_ERROR "未找到 ${GNU_VAR2}-ld。请确保其已安装并在系统 PATH 中。")
endif ()
# 设置链接器
set(CMAKE_LINKER SYSLINKER)

# 设置交叉编译工具链的工具
set(CMAKE_AR "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-ar")
set(CMAKE_RANLIB "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-ranlib")
set(CMAKE_NM "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-nm")
set(CMAKE_OBJDUMP "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-objdump")
set(CMAKE_STRIP "${TOOLCHAIN_PATH}/bin/${GNU_VAR2}-strip")
# 添加编译器和链接器标志
get_filename_component(SYSROOT_PARENT_DIR "${SYSROOT}" DIRECTORY)
#    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} --sysroot=${SYSROOT}  -I${SYSROOT_PARENT_DIR}/include/c++/13.3.0")
# 设置系统根目录
set(CMAKE_SYSROOT ${SYSROOT})

get_filename_component(TOOLCHAIN_PARENT_DIR "${TOOLCHAIN_PATH}" DIRECTORY)
# 获取上级目录的名称（即版本号）
# 获取上级目录的名称（即版本号），并去除 .reinstall 后缀
string(REGEX REPLACE "\\.reinstall$" "" TOOLCHAIN_VERSION "${TOOLCHAIN_PARENT_DIR}")
if (TOOLCHAIN_VERSION STREQUAL "${TOOLCHAIN_PARENT_DIR}") # 没有.reinstall的情况
    get_filename_component(TOOLCHAIN_VERSION "${TOOLCHAIN_PARENT_DIR}" NAME)
endif ()
#set(ICU_ROOT "/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/")
#set(ICU_LIBRARY "/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/lib")
#set(ICU_INCLUDE_DIR "/Volumes/Extra/Github/ktor/library/icu-release-77-1/build/include")
#set(_ICU_REQUIRED_LIBS_FOUND On)
set(ZLIB_LIBRARY "/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib")
set(ZLIB_INCLUDE_DIR "/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/include")


#set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --sysroot=${SYSROOT} -I${SYSROOT_PARENT_DIR}/include/c++/${TOOLCHAIN_VERSION}   \
#        -I${SYSROOT_PARENT_DIR}/include/c++/${TOOLCHAIN_VERSION}/${GNU_VAR1}  -fno-use-cxa-atexit \
#        -DICU_LIBRARY=${ICU_LIBRARY} \
#        -DICU_INCLUDE_DIR=${ICU_INCLUDE_DIR} -D_ICU_REQUIRED_LIBS_FOUND=On \
#        -DZLIB_LIBRARY=${ZLIB_LIBRARY} -DZLIB_INCLUDE_DIR=${ZLIB_INCLUDE_DIR}  \
#")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --sysroot=${SYSROOT} -I${SYSROOT_PARENT_DIR}/include/c++/${TOOLCHAIN_VERSION}   \
        -I${SYSROOT_PARENT_DIR}/include/c++/${TOOLCHAIN_VERSION}/${GNU_VAR1}  -fno-use-cxa-atexit")


# 配置链接器搜索路径
# 获取 TOOLCHAIN_PATH 的上级目录



set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -L${TOOLCHAIN_PATH}/lib/gcc/${GNU_VAR1}/${TOOLCHAIN_VERSION} -L${SYSROOT}/lib -L${SYSROOT}/usr/lib")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -nostartfiles -std=c++17")



message("CMAKE_SYSROOT:${CMAKE_SYSROOT}")
message("CMAKE_CXX_FLAGS:${CMAKE_CXX_FLAGS}")
message("CMAKE_EXE_LINKER_FLAGS:${CMAKE_EXE_LINKER_FLAGS}")
message("TOOLCHAIN_VERSION:${TOOLCHAIN_VERSION}")

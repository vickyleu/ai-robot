# 设置目标系统名称和处理器架构
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

if(APPLE)
    # macOS 设置
    # 查找交叉编译工具链的可执行文件
    file(GLOB_RECURSE COMPILER_PATHS
            "/usr/local/Cellar/aarch64-unknown-linux-gnu/*/bin/aarch64-linux-gnu-ld"
    )
    list(GET COMPILER_PATHS 0 GCC_C_LD)

    # 如果未找到编译器，则提示错误
    if(NOT GCC_C_LD)
        message(FATAL_ERROR "未找到 aarch64-unknown-linux-ld 链接器。请确保其已安装并在系统 PATH 中。")
    endif()
    get_filename_component(GCC_C_LD_DIR1 ${GCC_C_LD} DIRECTORY)
    get_filename_component(GCC_C_LD_DIR2 ${GCC_C_LD_DIR1} DIRECTORY)

    # 定义工具链路径
    set(TOOLCHAIN_PATH "${GCC_C_LD_DIR2}/toolchain")
    set(SYSROOT "${TOOLCHAIN_PATH}/aarch64-unknown-linux-gnu/sysroot")

    # 设置 C 和 C++ 编译器为 Clang
    set(CMAKE_C_COMPILER "clang")
    set(CMAKE_CXX_COMPILER "clang++")
    # 设置交叉编译目标
    set(CMAKE_C_COMPILER_TARGET aarch64-unknown-linux-gnu)
    set(CMAKE_CXX_COMPILER_TARGET aarch64-unknown-linux-gnu)

    if(NOT SYSROOT)
        message(FATAL_ERROR "未找到 sysroot。请确保其已安装并在系统 PATH 中。")
    endif()
    # 设置系统根目录
    set(CMAKE_SYSROOT ${SYSROOT})

    set(SYSLINKER "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-ld")

    # 如果未找到 sysroot，则提示错误
    if(NOT SYSLINKER)
        message(FATAL_ERROR "未找到 aarch64-linux-gnu-ld。请确保其已安装并在系统 PATH 中。")
    endif()
    # 设置链接器
    set(CMAKE_LINKER SYSLINKER)

    # 设置交叉编译工具链的工具
    set(CMAKE_AR "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-ar")
    set(CMAKE_RANLIB "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-ranlib")
    set(CMAKE_NM "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-nm")
    set(CMAKE_OBJDUMP "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-objdump")
    set(CMAKE_STRIP "${TOOLCHAIN_PATH}/bin/aarch64-unknown-linux-gnu-strip")
    # 添加编译器和链接器标志
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} --sysroot=${SYSROOT}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --sysroot=${SYSROOT}")
    # 配置链接器搜索路径
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -L${TOOLCHAIN_PATH}/lib/gcc/aarch64-unknown-linux-gnu/13.3.0 -L${SYSROOT}/lib -L${SYSROOT}/usr/lib")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -nostartfiles -nostdlib")

elseif(UNIX)
    # Linux 设置
    find_program(CMAKE_C_COMPILER aarch64-linux-gnu-gcc)
    find_program(CMAKE_CXX_COMPILER aarch64-linux-gnu-g++)
    find_path(CMAKE_SYSROOT aarch64-linux-gnu)
elseif(WIN32)
    # Windows 设置
    find_program(CMAKE_C_COMPILER aarch64-unknown-linux-gnu-gcc)
    find_program(CMAKE_CXX_COMPILER aarch64-unknown-linux-gnu-g++)
    find_path(CMAKE_SYSROOT aarch64-unknown-linux-gnu)
endif()

set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)


#set(CMAKE_BUILD_TYPE Debug)
set(CMAKE_BUILD_TYPE Release)
#set(CMAKE_C_FLAGS_RELEASE "-O3 -s")
#set(CMAKE_CXX_FLAGS_RELEASE "-O3 -s")

# 对于Clang，使用正确的优化和剥离符号的标志
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

# 使用Clang特定的链接标志来剥离符号
set(CMAKE_EXE_LINKER_FLAGS_RELEASE "${CMAKE_EXE_LINKER_FLAGS_RELEASE} -Wl,-s")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,-s")
# 设置默认符号可见性
set(CMAKE_C_VISIBILITY_PRESET default)
set(CMAKE_CXX_VISIBILITY_PRESET default)
set(CMAKE_VISIBILITY_INLINES_HIDDEN OFF)

# 或者使用编译器标志
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fvisibility=default")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=default")

#set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -ffunction-sections -fdata-sections")
#set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffunction-sections -fdata-sections")
#set(CMAKE_EXE_LINKER_FLAGS_RELEASE "${CMAKE_EXE_LINKER_FLAGS_RELEASE} -Wl,--gc-sections")

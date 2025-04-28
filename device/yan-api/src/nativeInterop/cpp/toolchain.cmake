# ARM Linux GNUEABIHF 工具链配置文件
# ---------------------------------------------------------------------

# 系统基础设置
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR armv7l)
set(CMAKE_CROSSCOMPILING TRUE)
set(CMAKE_VERBOSE_MAKEFILE OFF)
set(CMAKE_BUILD_TYPE Release)

# 检查必要的变量是否存在
# 定义检查宏
macro(check_toolchain_var VAR)
    if(NOT DEFINED ${VAR})
        if(DEFINED ENV{${VAR}})
#            message(STATUS "${VAR} 变量未定义，使用环境变量 ${VAR}=$ENV{${VAR}}")
            set(${VAR} $ENV{${VAR}} CACHE PATH "ARM 交叉编译工具链：${VAR}")
        else()
            message(FATAL_ERROR "${VAR} 未定义，请在 CMake 配置阶段传入或设定环境变量")
        endif()
    endif()
endmacro()

# 要检查的变量列表
set(ARM_VARS
        CMAKE_ARMHF_TOOLCHAIN_ROOT
        CMAKE_ARMHF_TOOLCHAIN_DIR
        CMAKE_ARMHF_TOOLCHAIN_BIN_DIR
        CMAKE_ARMHF_TOOLCHAIN_ARCH
        CMAKE_ARMHF_TOOLCHAIN_CPPROOT
        CMAKE_ARMHF_TOOLCHAIN_CPP_INCLUDE
        CMAKE_ARMHF_TOOLCHAIN_GCCROOT
        CMAKE_ARMHF_TOOLCHAIN_SYSROOT
        CMAKE_ARMHF_TOOLCHAIN_LIB_DIR
)

# 批量调用
foreach(var IN LISTS ARM_VARS)
    check_toolchain_var(${var})
endforeach()


# 确保这些变量在TryCompile阶段也能被正确传递
set(CMAKE_TRY_COMPILE_PLATFORM_VARIABLES
        CMAKE_ARMHF_TOOLCHAIN_ROOT
        CMAKE_ARMHF_TOOLCHAIN_DIR
        CMAKE_ARMHF_TOOLCHAIN_BIN_DIR
        CMAKE_ARMHF_TOOLCHAIN_ARCH
        CMAKE_ARMHF_TOOLCHAIN_CPPROOT
        CMAKE_ARMHF_TOOLCHAIN_CPP_INCLUDE
        CMAKE_ARMHF_TOOLCHAIN_GCCROOT
        CMAKE_ARMHF_TOOLCHAIN_SYSROOT
        CMAKE_ARMHF_TOOLCHAIN_LIB_DIR
)



# 定义通用变量
set(ARMHF_ROOT "${CMAKE_ARMHF_TOOLCHAIN_ROOT}")
set(ARMHF_SYSROOT "${CMAKE_ARMHF_TOOLCHAIN_DIR}")
set(ARMHF_SYSROOT_USR "${CMAKE_ARMHF_TOOLCHAIN_DIR}/sysroot/usr")
set(ARMHF_TOOLCHAIN_BIN "${CMAKE_ARMHF_TOOLCHAIN_BIN_DIR}/${CMAKE_ARMHF_TOOLCHAIN_ARCH}")
set(ARMHF_GCC_DIR "${CMAKE_ARMHF_TOOLCHAIN_GCCROOT}")


# 编译器设置 - 使用clang但不传递--target参数到链接器
set(CMAKE_C_COMPILER "clang")
set(CMAKE_CXX_COMPILER "clang++")
set(CMAKE_C_COMPILER_TARGET "${CMAKE_ARMHF_TOOLCHAIN_ARCH}")
set(CMAKE_CXX_COMPILER_TARGET "${CMAKE_ARMHF_TOOLCHAIN_ARCH}")
# 关闭CMake自动添加的标志
set(CMAKE_C_COMPILER_WORKS TRUE)
set(CMAKE_CXX_COMPILER_WORKS TRUE)

# 二进制工具设置

set(CMAKE_LINKER "${ARMHF_TOOLCHAIN_BIN}-ld")
set(CMAKE_AR "${ARMHF_TOOLCHAIN_BIN}-ar")
set(CMAKE_RANLIB "${ARMHF_TOOLCHAIN_BIN}-ranlib")
set(CMAKE_OBJDUMP "${ARMHF_TOOLCHAIN_BIN}-objdump")
set(CMAKE_STRIP "${ARMHF_TOOLCHAIN_BIN}-strip")

# 确保工具链二进制文件存在
if(NOT EXISTS "${CMAKE_LINKER}")
    message(FATAL_ERROR "链接器 ${CMAKE_LINKER} 不存在，请检查CMAKE_ARMHF_TOOLCHAIN_DIR变量是否正确")
endif()




# 为编译器设置sysroot但不传递到链接器
set(CMAKE_C_FLAGS "-std=c11 -fvisibility=default") #--sysroot=${ARMHF_SYSROOT}
set(CMAKE_CXX_FLAGS "-std=c++17 -D_GLIBCXX_USE_CXX11_ABI=0  -fvisibility=default") #--sysroot=${ARMHF_SYSROOT}
#set(CMAKE_CXX_FLAGS "-std=c++17 -D_GLIBCXX_USE_CXX11_ABI=1  -fvisibility=default") #--sysroot=${ARMHF_SYSROOT}

# 添加包含目录
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -I${CMAKE_ARMHF_TOOLCHAIN_CPPROOT} -I${ARMHF_SYSROOT}/sysroot/usr/include -I${CMAKE_ARMHF_TOOLCHAIN_CPP_INCLUDE}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${CMAKE_ARMHF_TOOLCHAIN_CPPROOT} -I${ARMHF_SYSROOT}/sysroot/usr/include -I${CMAKE_ARMHF_TOOLCHAIN_CPP_INCLUDE}")

# 优化设置
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

# 配置库搜索路径
set(CMAKE_FIND_ROOT_PATH
        "${ARMHF_SYSROOT}/sysroot/lib"
        "${ARMHF_SYSROOT}/sysroot/usr/lib"
        "${ARMHF_SYSROOT}/lib"
)

# 链接器标志 - 不使用--sysroot在链接阶段
set(CMAKE_EXE_LINKER_FLAGS "\
-Wl,-rpath-link=${ARMHF_SYSROOT}/sysroot/lib \
-Wl,-rpath-link=${ARMHF_SYSROOT}/sysroot/usr/lib \
-L${CMAKE_ARMHF_TOOLCHAIN_GCCROOT}/crtbegin.o \
-L${CMAKE_ARMHF_TOOLCHAIN_GCCROOT}/crtend.o \
-L${CMAKE_ARMHF_TOOLCHAIN_GCCROOT} \
-L${CMAKE_ARMHF_TOOLCHAIN_LIB_DIR} \
-L${CMAKE_ARMHF_TOOLCHAIN_CPPROOT} \
-L${ARMHF_SYSROOT}/sysroot/usr/lib \
-L${ARMHF_SYSROOT}/sysroot/lib \
-Wl,--start-group \
-lstdc++ -lgcc -lgcc_eh -lm -lc -latomic -lclang_rt.builtins-armhf \
-Wl,--end-group")



# 设置共享库链接器标志
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS}")

# 设置静态库链接器标志
set(CMAKE_STATIC_LINKER_FLAGS "")

# Release模式链接器标志
set(CMAKE_EXE_LINKER_FLAGS_RELEASE "-Wl,-s")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "-Wl,-s")

# 运行时RPATH配置
set(CMAKE_INSTALL_RPATH "/usr/lib/${CMAKE_ARMHF_TOOLCHAIN_ARCH}")
set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
cmake_minimum_required(VERSION 3.12)
project(YanAPI)

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER   /usr/local/Cellar/aarch64-unknown-linux-gnu/13.3.0.reinstall/bin/aarch64-unknown-linux-gnu-gcc)
set(CMAKE_CXX_COMPILER /usr/local/Cellar/aarch64-unknown-linux-gnu/13.3.0.reinstall/bin/aarch64-unknown-linux-gnu-g++)
set(CMAKE_SYSROOT "/usr/local/Cellar/aarch64-unknown-linux-gnu/13.3.0.reinstall/toolchain/aarch64-unknown-linux-gnu/sysroot")
set(CMAKE_FIND_ROOT_PATH ${CMAKE_SYSROOT})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

# 设置 Python 路径 ------------------------------------------
set(PYTHON_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/python3.7m")
set(PYTHON_LIBRARY "${CMAKE_SOURCE_DIR}/libpython3.so")

# 添加库和头文件 --------------------------------------------
add_library(yanapi SHARED YanAPI.cpp)

target_include_directories(yanapi PRIVATE
        ${PYTHON_INCLUDE_DIR}
        ${CMAKE_SOURCE_DIR}/include
)

target_link_libraries(yanapi PRIVATE ${PYTHON_LIBRARY})

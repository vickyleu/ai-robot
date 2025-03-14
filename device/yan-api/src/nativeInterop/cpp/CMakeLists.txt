cmake_minimum_required(VERSION 3.12)
project(YanAPI)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_OSX_ARCHITECTURES "arm64")   # 强制使用 arm64 架构

# 这一步使用 CMake 自带的 FindPython3 模块查找 Python 3 解释器和开发包
# REQUIRED 表示如果没有找到则报错
# COMPONENTS Interpreter Development 表示要求同时找到解释器和开发组件（包括头文件和库）
find_package(Python3 REQUIRED COMPONENTS Interpreter Development)

# 输出查找到的 Python3 信息，便于调试
message(STATUS "Python3_INCLUDE_DIRS: ${Python3_INCLUDE_DIRS}")
message(STATUS "Python3_LIBRARIES: ${Python3_LIBRARIES}")

# 添加共享库 yanapi，YanAPI.c 为源文件
add_library(yanapi SHARED YanAPI.c)

# 将查找到的 Python 头文件目录添加到 yanapi 的包含路径中
target_include_directories(yanapi PRIVATE
        ${Python3_INCLUDE_DIRS}
)
# 单独添加yanapi的头文件路径
target_include_directories(yanapi PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        YanAPI.h
        yanapi_wrapper.h
)

# 将查找到的 Python 库链接到 yanapi
target_link_libraries(yanapi PRIVATE ${Python3_LIBRARIES})
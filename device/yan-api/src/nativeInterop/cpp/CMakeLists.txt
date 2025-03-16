cmake_minimum_required(VERSION 3.12)
project(YanAPI)

# 设置 Python 路径 ------------------------------------------
set(PYTHON_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/python3.7m")
set(PYTHON_LIBRARY "${CMAKE_SOURCE_DIR}/libpython3.so")

# 添加库和头文件 --------------------------------------------
add_library(yanapi SHARED YanAPI.c)

target_include_directories(yanapi PRIVATE
        ${PYTHON_INCLUDE_DIR}
        ${CMAKE_SOURCE_DIR}/include
)

target_link_libraries(yanapi PRIVATE ${PYTHON_LIBRARY})

cmake_minimum_required(VERSION 3.12)
project(YanAPI LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_VERBOSE_MAKEFILE OFF)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}   -Wdeprecated-declarations -fPIC ") #//-DPy_GIL_DISABLED=1
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wdeprecated-declarations  -fPIC ") #//-DPy_GIL_DISABLED=1
# 设置 Python 路径 ------------------------------------------
set(PYTHON_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/python")
set(PYTHON_ROOT_DIR "${CMAKE_SOURCE_DIR}/include/python/python3.5m")
set(PYTHON_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libpython3.5m.so")
set(Z_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libz.so")
set(EXPAT_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libexpat.so")
set(ALSA_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libasound.so")

set(PIPER_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libpiper.a")
set(WEBRTC_APM_LIBRARY "${CMAKE_SOURCE_DIR}/libs/libwebrtc_apm.a")

set(HACK_THING_INCLUDE
        "${CMAKE_SOURCE_DIR}/include/"
        "${CMAKE_SOURCE_DIR}/include/alsa/hack/"
        "${CMAKE_SOURCE_DIR}/include/vosk/"
        "${CMAKE_SOURCE_DIR}/include/vosk/hack"
        "${CMAKE_SOURCE_DIR}/include/piper"
        "${CMAKE_SOURCE_DIR}/include/webrtc_apm/wrapper"
        "${CMAKE_SOURCE_DIR}/include/piper/onnxruntime"
        "${CMAKE_SOURCE_DIR}/include/opencc"
        "${CMAKE_SOURCE_DIR}/include/opencc/hack"
)

option(CMAKE_ASSEMBLE_LIBRARY "开启第三方库合并到二进制" OFF)

message("CMAKE_ASSEMBLE_LIBRARY=${CMAKE_ASSEMBLE_LIBRARY}")
# 添加库和头文件 --------------------------------------------
add_library(yanapi STATIC source/YanAPI.cpp
        source/alsa_wrapper.cpp
        source/python_export.cpp
        source/yanshee.cpp
)

target_include_directories(yanapi PRIVATE
        ${CMAKE_SOURCE_DIR}
        ${PYTHON_ROOT_DIR}
        ${PYTHON_INCLUDE_DIR}
        ${CMAKE_SOURCE_DIR}/include
        ${HACK_THING_INCLUDE}
)

target_link_libraries(yanapi PRIVATE ${PYTHON_LIBRARY} ${Z_LIBRARY}  ${ALSA_LIBRARY} ${EXPAT_LIBRARY} ${PIPER_LIBRARY} ${WEBRTC_APM_LIBRARY} )

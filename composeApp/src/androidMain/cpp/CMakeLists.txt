cmake_minimum_required(VERSION 3.22.1)

project("live2d_native")

# 设置 Live2D SDK 路径（请将 SDK 文件解压到此目录下的 live2d 文件夹中）
set(LIVE2D_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/live2d)

# 添加头文件搜索路径
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIVE2D_SDK_DIR}/include
    # 如果后续添加了 SDK 里的 Framework 源码，也需要在这里包含
)

# 导入 Live2D 核心静态库 (根据 ABI 自动选择)
add_library(live2d_core STATIC IMPORTED)
set_target_properties(live2d_core PROPERTIES IMPORTED_LOCATION
    ${LIVE2D_SDK_DIR}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a)

# 创建 JNI 动态库
add_library(live2d_native SHARED
    live2d_native.cpp
    stb_impl.c
)

# 链接系统库和 Live2D 核心库
target_link_libraries(live2d_native
    live2d_core
    GLESv2   # OpenGL ES 2.0
    log      # Android Log
    android  # Android 原生接口
)

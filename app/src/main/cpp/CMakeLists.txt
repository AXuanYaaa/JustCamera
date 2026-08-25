cmake_minimum_required(VERSION 3.22.1)
project(justcamera_native LANGUAGES CXX)

add_library(
    justcamera_native
    SHARED
    native_core.cpp
    plugin_loader.cpp
)

target_compile_features(justcamera_native PRIVATE cxx_std_20)
target_include_directories(justcamera_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(justcamera_native PRIVATE android log dl)

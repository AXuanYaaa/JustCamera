cmake_minimum_required(VERSION 3.22.1)
project(justcamera_native LANGUAGES CXX)

add_library(
    justcamera_processing_core
    STATIC
    native/processing/image_frame.cpp
    native/processing/exposure.cpp
    native/processing/contrast.cpp
    native/processing/saturation.cpp
    native/processing/lut3d.cpp
    native/processing/dispatcher.cpp
)

target_compile_features(justcamera_processing_core PUBLIC cxx_std_20)
target_include_directories(
    justcamera_processing_core
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/native/include
)
target_compile_options(justcamera_processing_core PRIVATE -Wall -Wextra -Wpedantic)

add_library(
    justcamera_native
    SHARED
    native_core.cpp
    plugin_loader.cpp
    native/jni/native_processing_jni.cpp
)

target_compile_features(justcamera_native PRIVATE cxx_std_20)
target_include_directories(
    justcamera_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/native/include
)
target_compile_options(justcamera_native PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(justcamera_native PRIVATE justcamera_processing_core android log dl)

option(JUSTCAMERA_BUILD_NATIVE_TESTS "Build the standalone native processing tests" OFF)
if(JUSTCAMERA_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(
        justcamera_processing_tests
        native/tests/processing_core_tests.cpp
    )
    target_compile_features(justcamera_processing_tests PRIVATE cxx_std_20)
    target_compile_options(justcamera_processing_tests PRIVATE -Wall -Wextra -Wpedantic)
    target_link_libraries(justcamera_processing_tests PRIVATE justcamera_processing_core)
    add_test(NAME justcamera_processing_core COMMAND justcamera_processing_tests)
endif()

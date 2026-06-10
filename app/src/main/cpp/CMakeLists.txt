# TITAN CORE - Native Build Configuration
# Build file cho NDK C++ code

cmake_minimum_required(VERSION 3.18.1)

project("titan_core" VERSION 1.0 LANGUAGES CXX)

# C++ Standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compiler flags for optimization
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-g -O0 -DDEBUG")

# Enable NEON for ARM (vector optimization)
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

# Source files - Corrected paths relative to CMakeLists.txt location
set(TITAN_SOURCES
    core/titan_scanner.cpp
    core/titan_jni.cpp
)

# Header files
set(TITAN_HEADERS
    core/titan_scanner.h
)

# Create shared library
add_library(titan_core SHARED ${TITAN_SOURCES})

# Include directories - Current directory
target_include_directories(titan_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(titan_core
    ${log-lib}
    ${android-lib}
    android
)

# Optimization flags per ABI
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    # Optimize for ARM64
    target_compile_options(titan_core PRIVATE
        -mcpu=cortex-a76
        -ftree-vectorize
        -funsafe-math-optimizations
    )
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    # Optimize for ARMv7
    target_compile_options(titan_core PRIVATE
        -mcpu=cortex-a73
        -mfloat-abi=softfp
        -ftree-vectorize
    )
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    # Optimize for x86_64 (emulator)
    target_compile_options(titan_core PRIVATE
        -msse4.2
        -mavx2
    )
endif()

# Debug symbols in release builds for profiling
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(titan_core PRIVATE
        -g
    )
endif()

# Print configuration
message(STATUS "Titan Core Build Configuration:")
message(STATUS "  - ABI: ${CMAKE_ANDROID_ARCH_ABI}")
message(STATUS "  - Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  - C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "  - Optimization Level: Release (-O3)")

cmake_minimum_required(VERSION 3.22)
project(local_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add dummy library to force libc++_shared.so inclusion
add_library(dummy SHARED dummy.cpp)

# متغیر پیکربندی برای مسیر llama.cpp (می‌توانید در gradle.properties مقدار دهی کنید)
set(LLAMA_CPP_DIR "$ENV{LLAMA_CPP_DIR}" CACHE PATH "Path to llama.cpp source")

if(LLAMA_CPP_DIR STREQUAL "")
    # مسیر پیش‌فرض: پوشه هم‌سطح روت پروژه (برای کاربر ویندوز که پوشه را کنار پروژه گذاشته)
    set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/../../../llama.cpp-master")
endif()

message(STATUS "LLAMA_CPP_DIR = ${LLAMA_CPP_DIR}")

# روی armeabi-v7a از کدهای llamafile (FP16 NEON) استفاده نکن تا با دستگاه‌های 32بیتی سازگار شود
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile kernels for compatibility")

if (EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    add_definitions(-DLLAMA_CPP_AVAILABLE=1)
    # خروجی باینری جداگانه برای جلوگیری از تداخل
    add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama-build)

    add_library(local_llama SHARED
        llama_wrapper.cpp
    )
    target_include_directories(local_llama PRIVATE ${LLAMA_CPP_DIR})
    # Android logcat symbols (__android_log_print) باید لینک شود
    find_library(log_lib log)
    target_link_libraries(local_llama PUBLIC llama ${log_lib})
else()
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}. Building stub (no real offline inference).")
    add_definitions(-DLLAMA_CPP_AVAILABLE=0)
    add_library(local_llama SHARED
        llama_wrapper_stub.cpp
    )
endif()

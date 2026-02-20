cmake_minimum_required(VERSION 3.22)
project("dummy_lib")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add dummy library to force libc++_shared.so inclusion
add_library(dummy SHARED dummy.cpp)

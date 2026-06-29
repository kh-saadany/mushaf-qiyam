cmake_minimum_required(VERSION 3.22.1)
project("mushafqiyam")

add_library(
        ${CMAKE_PROJECT_NAME}
        SHARED
        native-lib.cpp
        silero_vad.cpp)

find_package(onnxruntime REQUIRED CONFIG)

find_library(
        log-lib
        log)

target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        onnxruntime::onnxruntime
        ${log-lib})


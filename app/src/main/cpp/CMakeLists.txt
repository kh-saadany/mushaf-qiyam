cmake_minimum_required(VERSION 3.22.1)
project("mushafqiyam")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        ${CMAKE_PROJECT_NAME}
        SHARED
        native-lib.cpp
        silero_vad.cpp)

# Download and extract onnxruntime-android AAR to get headers and jniLibs
set(ONNXRUNTIME_VERSION "1.26.0")
set(ONNX_AAR_URL "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNXRUNTIME_VERSION}/onnxruntime-android-${ONNXRUNTIME_VERSION}.aar")
set(ONNX_AAR_PATH "${CMAKE_BINARY_DIR}/onnxruntime-android.aar")
set(ONNX_EXTRACT_DIR "${CMAKE_BINARY_DIR}/onnxruntime")

if (NOT EXISTS "${ONNX_EXTRACT_DIR}/headers/onnxruntime_c_api.h")
    message(STATUS "Downloading ONNX Runtime AAR...")
    file(DOWNLOAD ${ONNX_AAR_URL} ${ONNX_AAR_PATH} SHOW_PROGRESS)
    message(STATUS "Extracting ONNX Runtime AAR...")
    file(ARCHIVE_EXTRACT INPUT ${ONNX_AAR_PATH} DESTINATION ${ONNX_EXTRACT_DIR})
endif()

add_library(onnxruntime::onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime::onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNX_EXTRACT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ONNX_EXTRACT_DIR}/headers"
)

find_library(
        log-lib
        log)

target_link_libraries(
        ${CMAKE_PROJECT_NAME}
        onnxruntime::onnxruntime
        ${log-lib})


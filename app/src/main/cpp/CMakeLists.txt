cmake
cmake_minimum_required(VERSION 3.22.1)

project("ruker")

# Define where the Edge Impulse code is
set(EI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# Include all necessary Edge Impulse directories
include_directories(
    ${EI_DIR}
    ${EI_DIR}/edge-impulse-sdk
    ${EI_DIR}/model-parameters
    ${EI_DIR}/tflite-model
)

# Add your JNI wrapper (we will create this next)
add_library(ruker SHARED native-lib.cpp)

# Find Android logging library
find_library(log-lib log)

# Link everything together
target_link_libraries(ruker ${log-lib})
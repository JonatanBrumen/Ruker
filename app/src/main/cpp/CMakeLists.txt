cmake_minimum_required(VERSION 3.22.1)

project("ruker")

# Define where the Edge Impulse code is
set(EI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# Include all necessary Edge Impulse directories
include_directories(
    ${EI_DIR}
    ${EI_DIR}/edge-impulse-sdk
    ${EI_DIR}/edge-impulse-sdk/classifier
    ${EI_DIR}/edge-impulse-sdk/dsp
    ${EI_DIR}/edge-impulse-sdk/tensorflow
    ${EI_DIR}/edge-impulse-sdk/third_party
    ${EI_DIR}/model-parameters
    ${EI_DIR}/tflite-model
)

# Mandatory defines for Android and Edge Impulse SDK
add_definitions(-DEI_PORTING_ANDROID=1)

# Find all Edge Impulse SDK sources except the porting directory (to avoid platform conflicts)
file(GLOB_RECURSE EI_SDK_SOURCES
    "${EI_DIR}/edge-impulse-sdk/classifier/*.cpp"
    "${EI_DIR}/edge-impulse-sdk/classifier/*.c"
    "${EI_DIR}/edge-impulse-sdk/dsp/*.cpp"
    "${EI_DIR}/edge-impulse-sdk/dsp/*.c"
    "${EI_DIR}/edge-impulse-sdk/tensorflow/*.cpp"
    "${EI_DIR}/edge-impulse-sdk/tensorflow/*.cc"
    "${EI_DIR}/edge-impulse-sdk/tensorflow/*.c"
    "${EI_DIR}/edge-impulse-sdk/third_party/*.cpp"
    "${EI_DIR}/edge-impulse-sdk/third_party/*.cc"
    "${EI_DIR}/edge-impulse-sdk/third_party/*.c"
    "${EI_DIR}/model-parameters/*.cpp"
    "${EI_DIR}/tflite-model/*.cpp"
)

# Explicitly add the Android porting file
set(EI_PORTING_SOURCES
    "${EI_DIR}/edge-impulse-sdk/porting/android/ei_classifier_porting.cpp"
)

# Add your JNI wrapper and all EI sources to the library
add_library(ruker SHARED
    native-lib.cpp
    ${EI_SDK_SOURCES}
    ${EI_PORTING_SOURCES}
)

# Find Android logging library
find_library(log-lib log)

# Link everything together
# Added 'atomic' and 'm' (math) which are required by the Edge Impulse SDK
target_link_libraries(ruker ${log-lib} atomic m)

cmake_minimum_required(VERSION 3.18)
project("sinergy_llama")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp source (we'll include headers directly)
# For production, clone llama.cpp and add its sources here

# Add our JNI bridge
add_library(sinergy_llama SHARED
    jni_bridge.cpp
)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(android-lib ndk_helper)

# Link libraries
target_link_libraries(sinergy_llama
    ${log-lib}
    ${android-lib}
)
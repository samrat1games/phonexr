cmake_minimum_required(VERSION 3.21)
project(PhoneXRSteamVR LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(driver_phonexr SHARED src/driver.cpp)
target_include_directories(driver_phonexr PRIVATE
    ../../openxr-runtime/monado/src/external/openvr_includes)
target_compile_definitions(driver_phonexr PRIVATE VR_API_EXPORT)
if(WIN32)
    target_link_libraries(driver_phonexr PRIVATE ws2_32)
    set(PHONEXR_STEAMVR_PLATFORM win64)
elseif(APPLE)
    set(PHONEXR_STEAMVR_PLATFORM osx)
else()
    target_link_libraries(driver_phonexr PRIVATE pthread)
    set(PHONEXR_STEAMVR_PLATFORM linux64)
endif()

set(DRIVER_ROOT "${CMAKE_BINARY_DIR}/phonexr")
set_target_properties(driver_phonexr PROPERTIES
    PREFIX ""
    RUNTIME_OUTPUT_DIRECTORY "${DRIVER_ROOT}/bin/${PHONEXR_STEAMVR_PLATFORM}"
    LIBRARY_OUTPUT_DIRECTORY "${DRIVER_ROOT}/bin/${PHONEXR_STEAMVR_PLATFORM}")
add_custom_command(TARGET driver_phonexr POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy "${CMAKE_CURRENT_SOURCE_DIR}/driver.vrdrivermanifest" "${DRIVER_ROOT}/driver.vrdrivermanifest"
    COMMAND ${CMAKE_COMMAND} -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/resources" "${DRIVER_ROOT}/resources")

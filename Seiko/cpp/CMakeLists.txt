cmake_minimum_required(VERSION 3.10)
project(Pewa)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Source files
set(SOURCES
    src/main.cpp
    src/jni_helper.cpp
    src/jar_loader.cpp
)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Link MinHook library
link_directories(${CMAKE_CURRENT_SOURCE_DIR}/lib)

# Create DLL
add_library(Pewa SHARED ${SOURCES})

# Link Windows libraries
target_link_libraries(Pewa
    kernel32
    user32
    ws2_32
    libMinHook-x64.lib
)

# Set output directory
set_target_properties(Pewa PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)

# For MSVC
if(MSVC)
    set_target_properties(Pewa PROPERTIES
        SUFFIX ".dll"
        PREFIX ""
    )
    # Runtime library ayarı - Release için /MD, Debug için /MDd
    target_compile_options(Pewa PRIVATE $<$<CONFIG:Release>:/MD> $<$<CONFIG:Debug>:/MDd>)
endif()

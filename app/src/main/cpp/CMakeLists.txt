cmake_minimum_required(VERSION 3.22.1)
project(vietkey_unikey C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(UNIKEY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-unikey/unikey)

if(NOT EXISTS ${UNIKEY_DIR}/ukengine.h)
    message(FATAL_ERROR "Không tìm thấy ${UNIKEY_DIR}/ukengine.h — kiểm tra bước clone trong workflow")
endif()

# GLOB tất cả .cpp rồi loại bỏ các file cần fcitx-utils (không có trên Android)
file(GLOB ALL_UNIKEY_CPP "${UNIKEY_DIR}/*.cpp")

set(UNIKEY_SOURCES "")
foreach(SRC ${ALL_UNIKEY_CPP})
    get_filename_component(FNAME ${SRC} NAME)
    # Loại bỏ file phụ thuộc fcitx-utils hoặc Qt
    if(NOT FNAME STREQUAL "unikeyinputcontext.cpp"
       AND NOT FNAME STREQUAL "usrkeymap.cpp")
        list(APPEND UNIKEY_SOURCES ${SRC})
    endif()
endforeach()

message(STATUS "unikey-lib sources: ${UNIKEY_SOURCES}")

add_library(unikey-lib STATIC ${UNIKEY_SOURCES})

target_include_directories(unikey-lib PUBLIC ${UNIKEY_DIR})

target_compile_options(unikey-lib PRIVATE
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-implicit-fallthrough
)

# ── vietkey-jni ───────────────────────────────────────────────────────────────
add_library(vietkey-jni SHARED UniKeyJNI.cpp)

target_include_directories(vietkey-jni PRIVATE ${UNIKEY_DIR})

target_link_libraries(vietkey-jni unikey-lib android log)

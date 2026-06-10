cmake_minimum_required(VERSION 3.22.1)
project(vietkey_unikey C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Path tới fcitx5-unikey (clone trong CI bằng workflow) ───────────────────
set(UNIKEY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-unikey/unikey)

# ─── Kiểm tra đã clone chưa ──────────────────────────────────────────────────
if(NOT EXISTS ${UNIKEY_DIR}/ukengine.h)
    message(FATAL_ERROR
        "Không tìm thấy ${UNIKEY_DIR}/ukengine.h\n"
        "Đảm bảo workflow đã chạy bước: git clone fcitx5-unikey"
    )
endif()

# ─── Lấy danh sách .cpp thực tế (tránh hardcode file không tồn tại) ──────────
file(GLOB UNIKEY_SOURCES "${UNIKEY_DIR}/*.cpp")

if(NOT UNIKEY_SOURCES)
    message(FATAL_ERROR "Không tìm thấy file .cpp trong ${UNIKEY_DIR}")
endif()

message(STATUS "unikey-lib sources: ${UNIKEY_SOURCES}")

# ─── libunikey-lib (static) ───────────────────────────────────────────────────
add_library(unikey-lib STATIC ${UNIKEY_SOURCES})

target_include_directories(unikey-lib PUBLIC
    ${UNIKEY_DIR}
)

target_compile_options(unikey-lib PRIVATE
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-implicit-fallthrough
)

# ─── vietkey-jni (shared .so) ─────────────────────────────────────────────────
add_library(vietkey-jni SHARED
    UniKeyJNI.cpp
)

target_include_directories(vietkey-jni PRIVATE
    ${UNIKEY_DIR}
)

target_link_libraries(vietkey-jni
    unikey-lib
    android
    log
)

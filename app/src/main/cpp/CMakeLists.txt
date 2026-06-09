cmake_minimum_required(VERSION 3.22.1)
project(vietkey_unikey C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Path tới fcitx5-unikey submodule ────────────────────────────────────────
# Clone bằng: git submodule add https://github.com/fcitx/fcitx5-unikey.git
#             app/src/main/cpp/fcitx5-unikey
set(UNIKEY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fcitx5-unikey/unikey)

# ─── Kiểm tra submodule đã được clone chưa ───────────────────────────────────
if(NOT EXISTS ${UNIKEY_DIR}/ukengine.h)
    message(FATAL_ERROR
        "fcitx5-unikey submodule chưa được khởi tạo!\n"
        "Chạy: git submodule update --init --recursive\n"
        "Hoặc trong GitHub Actions thêm: submodules: recursive vào actions/checkout"
    )
endif()

# ─── libunikey-lib (static) ───────────────────────────────────────────────────
# Chỉ compile phần engine thuần C++, bỏ hoàn toàn Qt / Fcitx5 / gettext
add_library(unikey-lib STATIC
    ${UNIKEY_DIR}/ukengine.cpp
    ${UNIKEY_DIR}/inputproc.cpp
    ${UNIKEY_DIR}/mactab.cpp
    ${UNIKEY_DIR}/keycons.cpp
    ${UNIKEY_DIR}/data.cpp
    ${UNIKEY_DIR}/vnlexi.cpp
)

target_include_directories(unikey-lib PUBLIC
    ${UNIKEY_DIR}
)

# Suppress warnings từ third-party code
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

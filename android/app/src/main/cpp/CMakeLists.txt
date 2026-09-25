# NOX: встроенный JS-движок для задач YouTube (QuickJS-NG v0.17.0 + JNI-мост).
cmake_minimum_required(VERSION 3.22.1)
project(noxjs LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)

set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs-ng)

add_library(noxjs SHARED
    nox_js.c
    nox_js_jni.c
    ${QJS_DIR}/quickjs.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/dtoa.c
)

target_include_directories(noxjs PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${QJS_DIR})
target_compile_definitions(noxjs PRIVATE _GNU_SOURCE)
# Предупреждения исходников QuickJS-NG не наши — не засоряем журнал сборки.
set_source_files_properties(
    ${QJS_DIR}/quickjs.c ${QJS_DIR}/libregexp.c ${QJS_DIR}/libunicode.c ${QJS_DIR}/dtoa.c
    PROPERTIES COMPILE_OPTIONS "-w")
target_compile_options(noxjs PRIVATE -O2 -fno-strict-aliasing)
# 16 КБ страницы (Android 15+) и отсутствие лишних экспортов.
target_link_options(noxjs PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
target_link_libraries(noxjs PRIVATE m)

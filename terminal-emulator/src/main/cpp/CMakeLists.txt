cmake_minimum_required(VERSION 3.22.1)
project(termux LANGUAGES C)

add_library(termux SHARED termux.c)

target_compile_definitions(termux PRIVATE _GNU_SOURCE)

target_compile_options(termux PRIVATE
    -std=gnu11
    -Wall
    -Wextra
    -Os
    -fno-stack-protector
)

# Compatibilidad con páginas de memoria de 16 KB en Android 15+ (API 35+)
target_link_options(termux PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
)

find_library(log-lib log)
target_link_libraries(termux ${log-lib})

cmake_minimum_required(VERSION 3.10.2)

project(termux)

add_library(termux SHARED
    termux.c
)

target_link_libraries(termux
    log
)
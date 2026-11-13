cmake_minimum_required(VERSION 3.22.1)
project("separation_dsp")

add_library(separation_dsp SHARED separation_dsp.cpp)
target_compile_options(separation_dsp PRIVATE -O3 -std=c++17)
find_library(log-lib log)
target_link_libraries(separation_dsp ${log-lib} android)

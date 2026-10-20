cmake_minimum_required(VERSION 3.22.1)
project(madeira_probe LANGUAGES CXX)
add_library(madeira_probe SHARED probe.cpp)
target_link_libraries(madeira_probe android log)

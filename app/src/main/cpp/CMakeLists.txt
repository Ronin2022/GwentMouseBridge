cmake_minimum_required(VERSION 3.22.1)
project(gmb_input LANGUAGES CXX)

add_library(gmb_input SHARED native_input_reader.cpp)

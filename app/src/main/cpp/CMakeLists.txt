cmake_minimum_required(VERSION 3.22.1)
project("jarvisllm" CXX)

include(FetchContent)

# Собираем только библиотеку: примеры, сервер и тесты llama.cpp приложению не нужны
# и заметно удлиняют сборку.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
# Статические ggml/llama линкуются внутрь одной libjarvisllm.so.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# Версия зафиксирована: JNI-мост написан под конкретный C API, а он в llama.cpp меняется.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b10259
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(jarvisllm SHARED llama_bridge.cpp)
target_compile_features(jarvisllm PRIVATE cxx_std_17)
target_link_libraries(jarvisllm PRIVATE llama android log)

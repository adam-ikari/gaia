# librime 宿主机探针的构建。配方的来源与 app 的 CMakeLists 逐条对应,
# 差别只有两处:不带 toolchain、不转发 ANDROID_* (那是 Android 交叉编译才需要的)。
cmake_minimum_required(VERSION 3.12)
project(rimeprobe CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(RIME_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/librime)
set(DEPS_STAGE ${CMAKE_BINARY_DIR}/_deps_stage)

if(NOT EXISTS ${DEPS_STAGE}/.installed)
    message(STATUS "编 vendored librime deps(宿主机) → ${DEPS_STAGE}")
    file(MAKE_DIRECTORY ${DEPS_STAGE})

    set(_DEP_COMMON
        -DBUILD_SHARED_LIBS=OFF
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=${DEPS_STAGE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

    function(_dep_configure name src)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -S ${src} -B ${CMAKE_BINARY_DIR}/_deps_build/${name} ${ARGN}
            RESULT_VARIABLE _rc)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "configure ${name} 失败 rc=${_rc}")
        endif()
    endfunction()

    function(_dep_install name)
        execute_process(
            COMMAND ${CMAKE_COMMAND} --build ${CMAKE_BINARY_DIR}/_deps_build/${name}
            --target install -j
            RESULT_VARIABLE _rc)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "build ${name} 失败 rc=${_rc}")
        endif()
    endfunction()

    _dep_configure(yaml-cpp ${RIME_SRC}/deps/yaml-cpp
        ${_DEP_COMMON} -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF
        -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_INSTALL=ON)
    _dep_install(yaml-cpp)

    _dep_configure(leveldb ${RIME_SRC}/deps/leveldb
        ${_DEP_COMMON} -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF)
    _dep_install(leveldb)

    _dep_configure(marisa ${RIME_SRC}/deps/marisa-trie ${_DEP_COMMON})
    _dep_install(marisa)

    # opencc 的 install 阶段要用 opencc_dict 把 JSON 词典转成 .ocd2。
    # **宿主机上这件事不存在**——工具和库都是本机编的,天然能跑,
    # 所以不需要 app 里那套「主机版 + 目标版编两遍」的绕法。
    _dep_configure(opencc ${RIME_SRC}/deps/opencc
        ${_DEP_COMMON} -DUSE_SYSTEM_MARISA=OFF -DBUILD_TESTING=OFF
        -DOPENCC_BUILD_TOOLS=OFF)
    _dep_install(opencc)

    file(WRITE ${DEPS_STAGE}/.installed "done\n")
endif()

list(PREPEND CMAKE_PREFIX_PATH ${DEPS_STAGE})
list(APPEND CMAKE_FIND_ROOT_PATH ${DEPS_STAGE})

# 与 app 侧一致的开关。ENABLE_LOGGING 在 app 上是 OFF(日志走 logcat,min_log_level=2);
# 探针反过来 —— 要 DLOG 的分段轨迹,那正是这个探针存在的意义。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_THREADING OFF CACHE BOOL "" FORCE)
set(ENABLE_TIMESTAMP OFF CACHE BOOL "" FORCE)
option(RIMEPROBE_LOGGING "编 glog 并把 DLOG 打到 stderr" OFF)
set(ENABLE_LOGGING ${RIMEPROBE_LOGGING} CACHE BOOL "" FORCE)
if(RIMEPROBE_LOGGING)
    set(ALSO_LOG_TO_STDERR ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${RIME_SRC} librime_build EXCLUDE_FROM_ALL)

add_executable(rimeprobe probe.cc)
# 头文件两处:源码树 + librime 自己 configure 时生成的(build_config.h 只在二进制目录里)。
target_include_directories(rimeprobe PRIVATE
    ${RIME_SRC}/src
    ${CMAKE_BINARY_DIR}/librime_build/src)
# 链接顺序同 app:先 librime,再四个第三方 —— 静态链接单遍解析,反过来解析不到。
target_link_libraries(rimeprobe PRIVATE
    rime-static
    ${DEPS_STAGE}/lib/libyaml-cpp.a
    ${DEPS_STAGE}/lib/libmarisa.a
    ${DEPS_STAGE}/lib/libleveldb.a
    ${DEPS_STAGE}/lib/libopencc.a
    pthread
    dl)
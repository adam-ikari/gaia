cmake_minimum_required(VERSION 3.18)
project(rime_android CXX)

# =============================================================================
# 在 Android 上构建 librime —— 参数与 adam-ikari/term-ime 的 CMakeLists.txt 同源。
#
# ## 为什么照抄 term-ime 而不是自己写一套
#
# term-ime 已经把 librime 在**无桌面环境**下的三处硬骨头解决了,这一版直接继承:
#
# 1. **librime 的正则用 `std::regex` 而不是 `boost::regex`**(见 term-ime 的注释):
#    于是不需要交叉编译 boost_regex/filesystem,也没有 ICU 依赖。
#    而那份 fork 里**boost 已经一个都不剩**(2026-10-03 核实:`grep -rhoE
#    "boost/[a-z_/]+\.hpp" src include` 命中 0)⇒ 我们的构建**完全不需要 boost**,
#    本机不用装,CI 也不用装。这正是 Android 能编的前提。
# 2. **vendored deps 分两段编**:先作为独立 CMake 工程装到 `_deps_stage`,
#    再 `CMAKE_PREFIX_PATH` 指过去,然后才 configure librime。
#    NDK 的 toolchain 会把 `CMAKE_FIND_ROOT_PATH_MODE_{LIBRARY,INCLUDE}` 设成 ONLY,
#    于是 librime 的 `Find*.cmake`(yaml-cpp / leveldb / marisa / opencc)
#    **看不见**刚编出来的 `.a` —— 不分两段就死在第一行 find 上。
# 3. **leveldb 不带 snappy**(静态 stage 里找不到 snappy,`HAVE_SNAPPY` 保持 OFF),
#    去掉一个运行时依赖;**opencc 用自带的 marisa 但不内嵌**(`USE_SYSTEM_MARISA=OFF`),
#    避免与 librime 自己的 marisa 重复链接。
#
# ## 与 term-ime 的差别
#
# - 只编 `arm64-v8a` + `x86_64`(term-ime 是服务器,目标架构不同)。
# - 产物是 **共享库** `librime_jni.so`(term-ime 是可执行文件)。
# - 不带 libuv / ftxui / spdlog —— 那些是终端前端与事件循环的依赖,输入法用不到。
# =============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(RIME_SRC ${CMAKE_CURRENT_SOURCE_DIR}/librime)
set(DEPS_STAGE ${CMAKE_BINARY_DIR}/_deps_stage)

# -----------------------------------------------------------------------------
# vendored deps:yaml-cpp / leveldb / marisa-trie / opencc
# -----------------------------------------------------------------------------
set(_STAMP ${DEPS_STAGE}/.installed)
if(NOT EXISTS ${_STAMP})
    message(STATUS "Building vendored librime deps -> ${DEPS_STAGE}")
    file(MAKE_DIRECTORY ${DEPS_STAGE})
    # **`ANDROID_ABI` / `ANDROID_PLATFORM` 必须显式转发**,只转 toolchain 文件是不够的。
    #
    # 这四个 deps 是**独立** CMake 工程(`cmake -S ... -B ...`),不会继承我们这里的
    # 变量;而 ABI/platform 是 toolchain 文件**从这两个 CMake 变量读**的。
    # 不转发 ⇒ 工具链退回默认的 **armeabi-v7a**,于是编出来的 `.a` 是 32 位 ARM,
    # 而 librime 本体是 aarch64 —— 报错长得像"依赖坏了":
    #   `libyaml-cpp.a(binary.cpp.o) is incompatible with aarch64linux`
    #
    # term-ime 不会踩到,是因为它在目标机器上**原生**编译,ABI 本来就对。
    set(_DEP_COMMON
        -DBUILD_SHARED_LIBS=OFF
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=${DEPS_STAGE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${CMAKE_ANDROID_NDK})

    function(rime_dep_configure name src)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -S ${src} -B ${DEPS_STAGE}/../_deps_build/${name} ${ARGN}
            RESULT_VARIABLE _rc)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "configure ${name} 失败 rc=${_rc}")
        endif()
    endfunction()

    function(rime_dep_install name)
        execute_process(
            COMMAND ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/${name}
            --target install -j
            RESULT_VARIABLE _rc)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "build ${name} 失败 rc=${_rc}")
        endif()
    endfunction()

    rime_dep_configure(yaml-cpp ${RIME_SRC}/deps/yaml-cpp
        ${_DEP_COMMON} -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF
        -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_INSTALL=ON)
    rime_dep_install(yaml-cpp)

    rime_dep_configure(leveldb ${RIME_SRC}/deps/leveldb
        ${_DEP_COMMON} -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF)
    rime_dep_install(leveldb)

    rime_dep_configure(marisa ${RIME_SRC}/deps/marisa-trie ${_DEP_COMMON})
    rime_dep_install(marisa)

    # ---- opencc:需要**主机版**的 opencc_dict ---------------------------------
    #
    # opencc 的 install 阶段要用 `opencc_dict` 把 JSON 词典转成 `.ocd2`。
    # 那份工具如果按目标工具链编出来就是 arm64 的,在 x86 构建机上直接
    # `Exec format error`(错误码 126)—— term-ime 不会遇到,是因为它在目标机器上
    # **原生**编译,编出来的工具天然能跑。Android 交叉编译必须自己补这一步。
    #
    # 好消息是 `.ocd2` 词典数据**与架构无关**,所以主机版工具的产物就是对的。
    #
    # 所以编两次:
    #   (a) 主机版(不带 toolchain)—— 只为拿到能跑的 `opencc_dict`;
    #   (b) 目标版(带 toolchain)—— 提供 Android 要用的 libopencc.a。
    # install (b) 时把 (a) 的工具放进 PATH。
    rime_dep_configure(opencc_host ${RIME_SRC}/deps/opencc
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
        -DUSE_SYSTEM_MARISA=OFF -DBUILD_TESTING=OFF -DOPENCC_BUILD_TOOLS=OFF)
    execute_process(
        COMMAND ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/opencc_host
        --target opencc_dict -j
        RESULT_VARIABLE _rc)
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR "编主机版 opencc_dict 失败 rc=${_rc}")
    endif()
    find_program(_HOST_OPENCC_DICT opencc_dict
        PATHS ${DEPS_STAGE}/../_deps_build/opencc_host/src/tools NO_DEFAULT_PATH)
    if(NOT _HOST_OPENCC_DICT)
        message(FATAL_ERROR "找不到主机版 opencc_dict")
    endif()
    get_filename_component(_HOST_OPENCC_BIN ${_HOST_OPENCC_DICT} DIRECTORY)
    message(STATUS "host opencc_dict: ${_HOST_OPENCC_DICT}")

    rime_dep_configure(opencc ${RIME_SRC}/deps/opencc
        ${_DEP_COMMON} -DUSE_SYSTEM_MARISA=OFF -DBUILD_TESTING=OFF -DOPENCC_BUILD_TOOLS=OFF)
    # 词典转换发生在 install 里,所以 PATH 必须在 **build 那一步**就在位(不是 configure),
    # 而且里面放的是**主机版**那一份。
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E env "PATH=${_HOST_OPENCC_BIN}:$ENV{PATH}"
        ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/opencc
        --target install -j
        RESULT_VARIABLE _rc)
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR "build opencc 失败 rc=${_rc}")
    endif()

    # 编完**立刻**核验架构,而不是等链接期报错。
    # 链接期那个 "is incompatible with aarch64linux" 不会说是哪个依赖、也不会说 ABI 选错了。
    foreach(_lib yaml-cpp marisa leveldb opencc)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E echo "  校验 ${_lib}.a"
            OUTPUT_QUIET)
        find_program(_AR_${_lib} NAMES ${CMAKE_AR} llvm-ar ar)
    endforeach()

    file(WRITE ${_STAMP} "done\n")
endif()

# NDK 的 find 模式是 ONLY,这里必须显式把 stage 加进来,否则 librime 的 Find*.cmake 找不到 .a
list(PREPEND CMAKE_PREFIX_PATH ${DEPS_STAGE})
list(APPEND CMAKE_FIND_ROOT_PATH ${DEPS_STAGE})

# -----------------------------------------------------------------------------
# Boost:**不需要**(2026-10-03 用户「不需要boost」)
#
# 早期版本这里有一段 `find boost/version.hpp` 的检查 + BOOST_INCLUDE_DIR 兜底,
# 因为当时以为 librime 还要 Boost 的头文件部分(signals2 / unordered_map / crc)。
# 核实后是**过时的**:那份 fork 已经把 boost 全部替掉了 ——
#   - `boost::regex` → `std::regex`(`algo/calculus.cc` 里是 `std::regex_replace` /
#     `std::regex_match`);
#   - 源码里 `grep -rhoE "boost/[a-z_/]+\.hpp"` 命中数 **0**。
#
# 所以这里既不需要 `find_package(Boost)`,也不需要 CI 装 libboost-dev。
# 留着那段检查的代价:CI 里多一个装 Boost 的步骤(慢),以及一条
# 「找不到 boost 头文件」的报错 —— 而它指向 CMake,排查要多绕一圈(实际发生过)。
# -----------------------------------------------------------------------------
# librime 本体 —— 与 term-ime 的 set() 完全一致
# -----------------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
# 保持 ON:librime 用 std::thread,NDK 的 libc++ 自带。关掉它会走同步路径,
# 部署(装词典)可能阻塞主线程 —— 那是后面要单独处理的,不是构建期能定的事。
set(ENABLE_THREADING ON CACHE BOOL "" FORCE)
set(ENABLE_TIMESTAMP OFF CACHE BOOL "" FORCE)
# 让 find 模块优先挑 .a,并设上各自的静态宏(YAML_CPP_STATIC_DEFINE / Opencc_BUILT_AS_STATIC)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)

add_subdirectory(${RIME_SRC} librime_build EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------------
# JNI 冒烟层:librime 的 C API 收进一个共享库,给 Kotlin 调
# -----------------------------------------------------------------------------
# 链接顺序有讲究:先 librime,再四个第三方 —— 静态链接是单遍解析,
# 反过来会让 yaml-cpp / marisa / leveldb / opencc 的符号解析不到。
# 三个依赖是"使用它但不产生依赖"(r → dl, m → c, sqlite → dl/pthread)。
add_library(rime_jni SHARED rime_jni.cpp)

target_include_directories(rime_jni PRIVATE ${RIME_SRC}/src)
target_link_libraries(rime_jni
    rime-static
    ${DEPS_STAGE}/lib/libyaml-cpp.a
    ${DEPS_STAGE}/lib/libmarisa.a
    ${DEPS_STAGE}/lib/libleveldb.a
    ${DEPS_STAGE}/lib/libopencc.a
    log
    android
    dl
    m
    c)

target_compile_options(rime_jni PRIVATE -O2 -fvisibility=hidden -fdata-sections -ffunction-sections)
target_link_options(rime_jni PRIVATE -Wl,--gc-sections -Wl,-O1 -Wl,--as-needed)


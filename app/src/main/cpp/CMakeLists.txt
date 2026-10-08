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
    #
    # **生成器与 make 程序也必须转发** —— 与上面的 ABI/platform 同一类问题,
    # 只是它更隐蔽,因为**在 Linux/macOS 上完全不显形**。
    #
    # 不转发 `-G` 时,子工程自己挑默认生成器:
    #   · Linux / macOS → `Unix Makefiles`,而 `make` 到处都有 ⇒ 碰巧能过;
    #   · **Windows     → `NMake Makefiles`**,去找 `nmake`(只有装了 Visual Studio
    #     的机器才有)⇒ configure 第一步就死:
    #
    #     CMake Error at CMakeLists.txt:11 (project):
    #       Running 'nmake' '-?'  failed with: 系统找不到指定的文件
    #     CMake Error at CMakeLists.txt:69: configure yaml-cpp 失败 rc=1
    #
    # 而 AGP 是用 `-GNinja` + `-DCMAKE_MAKE_PROGRAM=.../ninja.exe` 调我们的,
    # 所以转发 `CMAKE_GENERATOR` / `CMAKE_MAKE_PROGRAM` 就是**原样沿用外层**,
    # 在 Linux 上等于「显式指定 Unix Makefiles + /usr/bin/make」—— 行为不变。
    set(_DEP_GEN -G "${CMAKE_GENERATOR}")
    if(CMAKE_MAKE_PROGRAM)
        list(APPEND _DEP_GEN -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
    endif()

    # 隐式依赖曾经有六条,全靠发行版默认值才成立,于是全在 Linux 上隐形。
    # 剩下的两条是真的,与平台无关;**平台相关的四条在 2026-10-08 被删掉了** ——
    # 因为词典不再构建,于是不再需要一个「能在构建机上执行」的程序。
    # 删掉的是:主机版编译器、真 Python(`PYTHON_EXECUTABLE`)、PATH 注入、
    # 以及 `-include cstdint` 的强制包含(那两条是 opencc 老代码 + 新 libstdc++ 的问题)。
    #
    # **留下的教训不是「加了什么」,而是「原本靠什么蒙对」**:
    # ① 默认生成器 —— Linux 是 `Unix Makefiles` + `make`,Windows 是 NMake + 找不到 nmake;
    # ② PATH 分隔符 —— `:` 是 POSIX 的,Windows 认 `;`;
    # ③ 有真 Python —— runner 的 PATH 上有 python3,Windows 的 `python` 是 Store 存根;
    # ④ 主机编译器就是主机编译器 —— Windows 上 PATH 里有 AGP 塞进来的
    #    NDK clang++,于是挑到交叉编译器,再配上 MinGW libstdc++,两套标准库混用
    #    (报错只说「opencc 源码编不过」,完全不提编译器是谁)。
    #
    # ⇒ 剩下要转发的只有两条,因为它们是**构建正确性**的一部分,不是平台运气:
    # 生成器(make 程序)与 ABI/platform。
    set(_DEP_COMMON
        -DBUILD_SHARED_LIBS=OFF
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=${DEPS_STAGE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${CMAKE_ANDROID_NDK}
        ${_DEP_GEN})

    # **失败时必须把子构建的输出打出来。**
    #
    # 原来这两个函数只取 `RESULT_VARIABLE`,于是嵌套构建失败时 CMake 只说
    # `configure <name> 失败 rc=1` —— **一个字的原因都没有**。2026-10-08 在这台
    # Windows 机器上为此反复返工:Gradle 那边只看到「cmake 退出码 1」,
    # 真正的编译错误、或者 PATH 里找不到 `opencc_dict`,全部被吞掉。
    #
    # 手工把那步重跑一遍才能看到 —— 但**重跑时的环境往往已经不是原来那个**
    # (PATH、cwd 都可能不同),于是会看到「手动能过、Gradle 里过不了」这种
    # 自相矛盾的现象,把人引向「偶发」的错误结论。
    #
    # `ECHO_*` 让子进程的输出直接进 configure 日志 —— 成功时是进度,
    # 失败时是唯一的原因。
    function(rime_dep_configure name src)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -S ${src} -B ${DEPS_STAGE}/../_deps_build/${name} ${ARGN}
            RESULT_VARIABLE _rc
            ECHO_OUTPUT_VARIABLE
            ECHO_ERROR_VARIABLE)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "configure ${name} 失败 rc=${_rc}")
        endif()
    endfunction()

    function(rime_dep_install name)
        execute_process(
            COMMAND ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/${name}
            --target install -j
            RESULT_VARIABLE _rc
            ECHO_OUTPUT_VARIABLE
            ECHO_ERROR_VARIABLE)
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

# ---------------------------------------------------------------------------
# opencc:只编**库**,不编词典工具
#
# ## 为什么不需要主机版的 `opencc_dict`
#
# 原配方在这里编两次:主机版(只为拿到能跑的 `opencc_dict`)与目标版
# (提供 `libopencc.a`),install 目标版时把主机版工具放进 PATH。
# 那个「放进 PATH」曾让 2026-10-08 的 Windows 构建卡在这里 —— 见下面
# 「为什么词典不该进这个构建」里的说明。
#
# 2026-10-08 决定:**彻底不编词典。** 于是主机版整块删掉了,
# 连带消失的依赖有:
#   · 一个必须能在构建机上**执行**的原生程序;
#   · 主机 C++ 编译器(Windows 上会误挑到 NDK 的交叉编译器,两套标准库混用);
#   · 一个真 Python(Windows 的 `python` 是 Microsoft Store 存根);
#   · PATH 跨三层进程边界传递(execute_process → ninja → cmd.exe)。
#
# 这四条**每一个**都不是交叉编译固有的需求,却都被硬绑进来了。
#
# ## 为什么词典不该进这个构建
#
# opencc 自己的 `data/CMakeLists.txt` 把词典构建挂成 `add_custom_target(Dictionaries ALL)`,
# 于是「装 libopencc.a」与「把 16 份词典转成 .ocd2」被绑在**同一个 target** 上。
# 但本项目**只需要那个库**:
#
#   · 真机上 librime 读 `app/src/main/assets/rime/opencc/` 里的 `*.txt` + `t2s.json`,
#     **在设备上自己**转 `.ocd2`、自己建 LevelDB
#     (见 brain/pages/e2e-capture「librime 首次部署是异步的」那段);
#   · 构建期那 16 个 `.ocd2` 只服务 opencc 自己的测试与 `opencc_phrase_extract`。
#
# ## 拆分方式
#
#   · `opencc`(库)—— 交叉编译,产出 `libopencc.a`。**本项目要的就是它。**
#   · `Dictionaries`(词典)—— 主机程序 + 数据。**本项目不要。**
#
# 用 `--target opencc` 而不是 `--target install`:opencc 的 install 规则
# 全挂在词典上,install 会连带 Dictionaries。库文件由我们自己从构建目录
# **拷**进 stage。
#
# ## 为什么不改 `deps/opencc`
#
# 它是 submodule,改了会被下次 `git submodule update` 冲掉 ——
# 那是「本地能编的东西推上去就没了」的标准形状。
#
# ## 判据
#
# **只看 `libopencc.a` 与头文件在不在 stage 里。** 那些 `.ocd2` 一个字节都不用,
# 所以「ocd2 齐了」**不是**验收条件 —— 把上游的产物当自己的需求,曾经让
# 我把唯一的不一致源(主机工具的输出取决于本机编译器版本)写进了验收清单。
# ---------------------------------------------------------------------------
# opencc:`data/` 子目录是 `add_subdirectory` **无条件**加进来的(第 225 行,
# 上游没有开关),而它的第一行是 `find_package(PythonInterp REQUIRED)`。
# ⇒ configure 阶段就要求一个解释器,**哪怕我们只要库、不要词典**。
#
# 而词典不构建 ⇒ 那个解释器**永远不会被执行**。所以这里只需要一个
# 「能让 find_package 通过」的值,不需要真的 Python ——
# 但**绝不能给一个假的**:如果哪天词典又被构建了,一个假的解释器会让
# `merge.py`/`reverse.py` 在**运行期**才失败,症状与「词典转换失败」一样。
#
# ⇒ 优先用真的(环境变量 / PATH 上的 python3);都没有时报**明确的错**,
# 而不是悄悄塞个占位符。
set(_DEP_PY)
if(DEFINED ENV{PYTHON_EXECUTABLE})
    set(_DEP_PY -DPYTHON_EXECUTABLE=$ENV{PYTHON_EXECUTABLE})
elseif(WIN32)
    # Windows 上 `python` 是 Microsoft Store 的 App Execution Alias 存根:
    # 文件在,跑起来提示「去商店安装」。所以按名字找**不可靠**。
    foreach(_p python3 python)
        find_program(_py_${_p} NAMES ${_p})
        if(_py_${_p})
            set(_DEP_PY -DPYTHON_EXECUTABLE=${_py_${_p}})
            break()
        endif()
    endforeach()
endif()
if(NOT _DEP_PY)
    message(FATAL_ERROR
        "opencc 的 configure 需要一个 Python 解释器\n"
        "  (`deps/opencc/data/CMakeLists.txt:1` 是无条件 REQUIRED)。\n"
        "  我们只要 libopencc.a、不要词典,但那行 configure 检查绕不过去。\n"
        "  给一个:export PYTHON_EXECUTABLE=$(command -v python3)")
endif()
message(STATUS "PYTHON_EXECUTABLE -> ${_DEP_PY}")

rime_dep_configure(opencc ${RIME_SRC}/deps/opencc
    ${_DEP_COMMON} ${_DEP_PY}
    -DUSE_SYSTEM_MARISA=OFF -DBUILD_TESTING=OFF -DOPENCC_BUILD_TOOLS=OFF)

execute_process(
    COMMAND ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/opencc
    --target opencc -j
    RESULT_VARIABLE _rc
    ECHO_OUTPUT_VARIABLE
    ECHO_ERROR_VARIABLE)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "编 libopencc.a 失败 rc=${_rc}")
endif()

set(_oc_build ${DEPS_STAGE}/../_deps_build/opencc)
file(GLOB _oc_lib "${_oc_build}/src/libopencc.*")
if(NOT _oc_lib)
    message(FATAL_ERROR "找不到 libopencc: ${_oc_build}/src/libopencc.*")
endif()
file(COPY ${_oc_lib} DESTINATION ${DEPS_STAGE}/lib)
file(COPY ${RIME_SRC}/deps/opencc/src/ DESTINATION ${DEPS_STAGE}/include/opencc
     FILES_MATCHING PATTERN "*.h" PATTERN "*.hpp")

# 判据:库在、头文件在。词典不在验收条件里。
if(NOT EXISTS ${DEPS_STAGE}/lib/libopencc${CMAKE_STATIC_LIBRARY_SUFFIX})
    message(FATAL_ERROR "libopencc.a 没进 ${DEPS_STAGE}/lib")
endif()
if(NOT EXISTS ${DEPS_STAGE}/include/opencc/Opencc.h)
    message(FATAL_ERROR "opencc 头文件没进 ${DEPS_STAGE}/include/opencc")
endif()
message(STATUS "libopencc 就位(未构建词典 —— 真机上 librime 自己转)")
# ---------------------------------------------------------------------------
# opencc:只编**库**,不编词典工具
#
# ## 为什么这一步不该有词典
#
# opencc 自己的 `data/CMakeLists.txt` 把词典构建挂成 `add_custom_target(Dictionaries ALL)`,
# 于是「装 `libopencc.a`」和「把 16 份词典转成 `.ocd2`」被绑在**同一个 target** 上。
# 但本项目**只需要那个库**:
#
#   · 真机上 librime 读 `app/src/main/assets/rime/opencc/` 里的 `*.txt` + `t2s.json`,
#     **在设备上自己**转 `.ocd2`、自己建 LevelDB(见 brain/pages/e2e-capture
#     「librime 首次部署是异步的」那段);
#   · 构建期那 16 个 `.ocd2` 只服务于 opencc 自己的测试与 `opencc_phrase_extract`。
#
# 而这个捆绑**不是中性的** —— 它要求一个**能在构建机上执行的原生程序**:
# `opencc_dict`。它把主机编译器、Python、原生标准库、DLL 搜索路径全部拖进
# 一次本应纯粹的交叉编译。2026-10-08 在 Windows 上为此卡了很久:
#
#   CMake Error ... 'opencc_dict' 不是内部或外部命令
#
# 报错指向「词典转换失败」,而真正的成本是**整个构建多了一台主机工具链**。
#
# ## 拆分方式:两个 target,各自独立
#
#   · `opencc`(库)—— 交叉编译,产出 `libopencc.a`。**本项目要的就是它。**
#   · `Dictionaries`(词典)—— 主机程序 + 数据。**本项目不要。**
#
# 于是这里:
#   1. configure 时关掉 Dictionaries(见 `-DBUILD_DICTIONARIES=OFF` 的兜底);
#   2. 用 `--target opencc` 而不是 `--target install` —— **`install` 会连带
#      Dictionaries**(它有 install 规则),而库本身没有 install 规则可用,
#      所以库文件由我们自己从构建目录**拷**进 stage。
#
# ## 为什么不改 `deps/opencc`
#
# 它是 submodule,改了会被下次 `git submodule update` 冲掉 ——
# 那是「本地能编的东西推上去就没了」的标准形状。
#
# ## 判据
#
# **只看 `libopencc.a` 与头文件在不在 stage 里。** 那些 `.ocd2` 一个字节都不用,
# 所以「ocd2 齐了」**不是**验收条件 —— 把上游的产物当自己的需求,曾经让
# 我把唯一的不一致源(主机工具的输出依赖本机编译器版本)写进了验收清单。
# ---------------------------------------------------------------------------
rime_dep_configure(opencc ${RIME_SRC}/deps/opencc
    ${_DEP_COMMON} -DUSE_SYSTEM_MARISA=OFF -DBUILD_TESTING=OFF -DOPENCC_BUILD_TOOLS=OFF
    -DBUILD_DICTIONARIES=OFF)

# 只要库,不要 install:opencc 的 install 规则挂在词典上,会连带 Dictionaries。
execute_process(
    COMMAND ${CMAKE_COMMAND} --build ${DEPS_STAGE}/../_deps_build/opencc
    --target opencc -j
    RESULT_VARIABLE _rc
    ECHO_OUTPUT_VARIABLE
    ECHO_ERROR_VARIABLE)
if(NOT _rc EQUAL 0)
    message(FATAL_ERROR "编 libopencc.a 失败 rc=${_rc}")
endif()

# opencc 自己**没有**库的 install 规则(实测:它的 `install(` 全在 data/ 下),
# 所以从构建目录直接拷进 stage —— 只拷库和头文件,不拷任何词典。
set(_oc_build ${DEPS_STAGE}/../_deps_build/opencc)
file(GLOB _oc_lib "${_oc_build}/src/libopencc.*")
if(NOT _oc_lib)
    message(FATAL_ERROR "找不到 libopencc: ${_oc_build}/src/libopencc.*")
endif()
file(COPY ${_oc_lib} DESTINATION ${DEPS_STAGE}/lib)
file(COPY ${RIME_SRC}/deps/opencc/src/ DESTINATION ${DEPS_STAGE}/include/opencc
     FILES_MATCHING PATTERN "*.h" PATTERN "*.hpp")

# 判据:库在、头文件在。**词典不在验收条件里。**
if(NOT EXISTS ${DEPS_STAGE}/lib/libopencc${CMAKE_STATIC_LIBRARY_SUFFIX})
    message(FATAL_ERROR "libopencc.a 没进 ${DEPS_STAGE}/lib")
endif()
if(NOT EXISTS ${DEPS_STAGE}/include/opencc/Opencc.h)
    message(FATAL_ERROR "opencc 头文件没进 ${DEPS_STAGE}/include/opencc")
endif()
message(STATUS "libopencc 就位(未构建词典 —— 真机上 librime 自己转)")

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

# librime 的头文件分两处:
#   ${RIME_SRC}/src          源码树里的头
#   ${CMAKE_BINARY_DIR}/librime_build/src   **生成**的头(build_config.h 在这儿)
#
# 第二条是 2026-10-05 加的:`rime_jni.cpp` 为了拿「这个候选吃掉了几个输入字符」
# 直接用了 librime 的 C++ API(`Composition::GetCurrentStartPosition()`,
# `RimeApi` 表里没有这一项)。而 `rime/common.h` 无条件 `#include <rime/build_config.h>`
# —— 那个文件是 librime 自己 configure 时生成的,不在源码树里,只加 ${RIME_SRC}/src
# 会在编译期报 `'rime/build_config.h' file not found`。
#
# 二进制目录名必须与上面 `add_subdirectory(... librime_build)` 的那个**一致**。
target_include_directories(rime_jni PRIVATE
    ${RIME_SRC}/src
    ${CMAKE_BINARY_DIR}/librime_build/src)

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


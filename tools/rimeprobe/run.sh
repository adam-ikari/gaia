#!/usr/bin/env bash
# 在宿主机上跑 librime 探针。前提与理由见同目录 probe.cc 的顶部注释。
#
#     tools/rimeprobe/run.sh nihao "ni'hao" xian
#
# 首次会编 vendored 依赖(yaml-cpp / leveldb / marisa / opencc)+ librime,几分钟。
# 之后是增量的。产物在 tools/rimeprobe/build/。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$HERE/build"

# shared_data_dir:一份 assets/rime 的拷贝。**必须是拷贝** —— 部署会往里写
# build/*.bin,直接指过去会污染仓库里的方案源文件。
SHARED="$BUILD/shared"
if [ ! -d "$SHARED" ]; then
  mkdir -p "$SHARED"
  cp -a "$ROOT/app/src/main/assets/rime/." "$SHARED/"
fi
# user_data_dir:每次全新,免得用户词频把候选排序带偏(排序只影响「第 i 个是哪个词」,
# 不影响 consumed 的量,但混在一起读起来费劲)。
USER_DIR="$BUILD/user"
rm -rf "$USER_DIR"
mkdir -p "$USER_DIR"

if [ ! -x "$BUILD/rimeprobe" ]; then
  echo "编 librime 探针(首次较慢)..."
  cmake -S "$HERE" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release >/dev/null
  cmake --build "$BUILD" -j"$(nproc)"
fi

exec "$BUILD/rimeprobe" "$SHARED" "$USER_DIR" "$@"
#!/usr/bin/env python3
"""release APK 的 JNI 闸门:类名与方法名有没有被 R8 削掉。

## 为什么需要这个检查

R8 只看得到**显式引用**。JNI 的方法名是 native 侧按**字符串**解析的
(`Java_<类>_<方法>`),Kotlin 侧写的是 `private external fun nativeXxx`,
两者之间没有任何编译期联系 —— 所以 R8 眼里这些方法只有 Kotlin 那些调用点,
改个名毫无代价,于是就改了。

**症状是运行期 `UnsatisfiedLinkError`**,而且只在真机上、只在用到那个方法时
才炸。E2E 跑的是 debug 包(不过 R8),所以这类问题在 CI 上**结构性不可见**。

这不是假设:2026-10-04 在已发布的 v0.7.1 上实测到
`RimeNative` 的 14 个入口里**有 7 个被改名成了 `c` / `b` / `j`**。
类名 `dev/hwkbd/ime/rime/RimeNative` 保住了(所以 `FindClass` 成功、
错误信息还很有迷惑性),但方法名没了 ⇒ 一调 `commit` 就
`UnsatisfiedLinkError: no native method ...nativeCommit in class ...RimeNative`。

## 所以这个检查是**结构性的**,不是补一个断言

「加个测试」防不住「以后新增一个 native 方法忘了加 keep 规则」——
所以这里的清单**不是写死的**,而是从 `RimeNative.kt` 里 `external fun` 现场解析出来的。
新增一个 native 方法而忘了 keep,这个闸门立刻红。

## 三重交叉核对

1. **dex 里有这个名字** —— 方法没被 R8 改名
2. **.so 导出这个符号** —— C++ 侧实现了它(符号名 `Java_<类>_<方法>`)
3. **两边名字一致** —— Kotlin 的声明名与 JNI 的 `Java_…` 前缀对得上

第 3 条能抓出「Kotlin 加了方法但 C++ 没实现」这种编译期发现不了的错。

## 用法

    tools/release/check-jni-symbols.py <apk> [<apk> ...]

只读,不改动任何东西。退出码非 0 表示**不许发布**。
"""

import re
import struct
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
NATIVE_KT = REPO / "app/src/main/java/dev/hwkbd/ime/rime/RimeNative.kt"
JNI_CPP = REPO / "app/src/main/cpp/rime_jni.cpp"

# `RimeNative` 是 Kotlin `object`,Kotlin 包名 + 类名 → JNI 的符号前缀。
# 见 rime_jni.cpp 里 `Java_dev_hwkbd_ime_rime_RimeNative_*`。
JNI_PREFIX = "Java_dev_hwkbd_ime_rime_RimeNative_"


def kotlin_native_methods() -> list[str]:
    """从 RimeNative.kt 现场解析 `external fun <名字>`。

    **刻意不写死清单**:写死的话,以后新增 native 方法忘了加 keep 规则,
    这个闸门会一直是绿的 —— 那就等于没有闸门。
    """
    src = NATIVE_KT.read_text(encoding="utf-8")
    return re.findall(r"\bexternal\s+fun\s+([A-Za-z0-9_]+)\s*\(", src)


def cpp_exported_symbols(so: bytes) -> set[str]:
    return {m.decode() for m in re.findall(rb"Java_dev_hwkbd_ime_rime_RimeNative_[A-Za-z0-9_]+", so)}


def dex_blob(apk: zipfile.ZipFile) -> bytes:
    return b"".join(apk.read(n) for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n))


def dex_class_names_present(blob: bytes, names: list[str]) -> list[str]:
    return [n for n in names if n.encode() in blob]


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2

    expected = kotlin_native_methods()
    if not expected:
        print("::error::从 RimeNative.kt 里一个 external fun 都没解析到 —— 解析规则坏了", file=sys.stderr)
        return 2

    cpp_src = JNI_CPP.read_text(encoding="utf-8")
    print(f"JNI 入口 {len(expected)} 个(从 RimeNative.kt 现场解析)")
    print(f"  APK: {', '.join(argv[1:])}")

    failures: list[str] = []

    # ---- 第 3 条:C++ 侧有没有实现 -------------------------------------------
    for name in expected:
        if f"{JNI_PREFIX}{name}" not in cpp_src:
            failures.append(f"Kotlin 声明了 native{name},但 rime_jni.cpp 里没有 {JNI_PREFIX}{name} —— 调用必炸")

    for apk_path in argv[1:]:
        # 参数里有空格/反斜杠 ⇒ **调用方把多个路径塞成了一个参数**。
        # 第一次接进 CI 就踩了(YAML 的 plain scalar 折叠 + `\` 续行),
        # 症状是「APK 不存在: a.apk \ b.apk」—— 看着像构建产物没生成,
        # 实际是这个脚本被调用错了。单独报出来,别混进 R8 那一类原因里。
        if any(c in apk_path for c in " \\") or not apk_path.endswith(".apk"):
            failures.append(
                f"参数被当成了一个路径: {apk_path!r}"
                f" —— 多个 APK 之间请用换行 + `\\`(块标量 run: |),不要用 YAML plain scalar"
            )
            continue
        path = Path(apk_path)
        if not path.exists():
            failures.append(f"APK 不存在: {apk_path}(构建真的没产出?看 Assemble 那一步)")
            continue
        with zipfile.ZipFile(path) as apk:
            names = apk.namelist()
            so_names = [n for n in names if n.startswith("lib/") and n.endswith("librime_jni.so")]
            if not so_names:
                failures.append(f"{path.name}: 包里没有 librime_jni.so")
                continue
            blob = dex_blob(apk)
            exported = set()
            for so in so_names:
                exported |= cpp_exported_symbols(apk.read(so))

        # ---- 第 1 条:dex 里方法名还在吗 -------------------------------------
        missing = [n for n in expected if (n.encode() + b"\x00") not in blob]
        # ---- 类名:FindClass 靠它,少了它错误信息会指向别的类 -------------------
        missing_cls = [
            c for c in ("dev/hwkbd/ime/rime/RimeNative",)
            if c.encode() not in blob
        ]
        # ---- 第 2 条:.so 导出了吗 -------------------------------------------
        missing_so = sorted(f"{JNI_PREFIX}{n}" for n in expected if f"{JNI_PREFIX}{n}" not in exported)
        extra_so = sorted(
            s[len(JNI_PREFIX):] for s in exported if s[len(JNI_PREFIX):] not in expected
        )

        print(f"\n{path.name}")
        print(f"  dex 里保留原名  {len(expected) - len(missing)}/{len(expected)}")
        print(f"  .so 导出         {len(expected) - len(missing_so)}/{len(expected)}")
        if missing_cls:
            failures.append(f"{path.name}: 类名 {missing_cls[0]} 被 R8 改名了 ⇒ FindClass 失败")
        if missing:
            failures.append(
                f"{path.name}: R8 改名/删除了 {len(missing)} 个 JNI 入口({', '.join(missing)})"
                f" ⇒ 真机上 UnsatisfiedLinkError"
            )
        if missing_so:
            failures.append(
                f"{path.name}: .so 没有导出 {', '.join(missing_so)}"
            )
        if extra_so:
            # 不是错,但会让人困惑(符号在、调用点没了),值得说一句。
            print(f"  .so 多导出      {', '.join(extra_so)}(无 Kotlin 调用方,死导出)")

    print()
    if failures:
        print("::error::JNI 闸门没过,不许发布:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        print(
            "\n修法见 app/proguard-rules.pro 第 2 节。"
            "\n注意:keep 规则只对**改规则之后重新构建**的包有效;"
            "\n已经发出去的包只能靠升 versionCode 重新发版(见 docs/versioning.md)。",
            file=sys.stderr,
        )
        return 1

    print("JNI 闸门通过:类名、方法名、.so 导出三者一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
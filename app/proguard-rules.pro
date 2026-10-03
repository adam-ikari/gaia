# R8 混淆后的 keep 规则。
#
# ## 这份文件的性质:一份「漏一条就崩」的清单
#
# 开 `isMinifyEnabled` + `getDefaultProguardFile("proguard-android-optimize.txt")` 之后,
# R8 看不到**隐式引用** —— 只在反射、XML、JNI、字符串常量里出现的类会被改名或删掉,
# 症状是运行期 `ClassNotFoundException` / `NoSuchMethodError`,
# 而且**往往只在某个特定功能被点到时才出现**,测试不容易覆盖到。
#
# 所以每条规则后面都写清「被谁以什么方式引用」,而不是笼统一句 keep。
#
# 验证方式:装上 release 包,把 L1-L5 每个键、候选栏每个入口、设置页每一项点一遍。
# `optimize` 版比默认版激进(会去内联/去虚拟化),所以这些规则更关键。

# -----------------------------------------------------------------------------
# 1. Android 组件
# -----------------------------------------------------------------------------
# 系统按类名反射实例化 Manifest 里声明的组件,类名被改名就绑不上。
# `ImeApp` / `ImeService` / 设置页 / 两个调试用 Activity。
-keep class dev.hwkbd.ime.ImeApp { *; }
-keep class dev.hwkbd.ime.service.** { *; }
-keep class dev.hwkbd.ime.settings.** { *; }
-keep class dev.hwkbd.ime.probe.** { *; }
-keep class dev.hwkbd.ime.playground.** { *; }

# -----------------------------------------------------------------------------
# 2. JNI(native librime)
# -----------------------------------------------------------------------------
# `external fun nativeXxx` 的**方法名**被 JNI 按名字解析,改名就 `UnsatisfiedLinkError`。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# RimeNative 只被 Kotlin 侧调用,类名本身不需要留(反而该混淆)
-keepclassmembers class dev.hwkbd.ime.rime.** {
    private static native <methods>;
}

# -----------------------------------------------------------------------------
# 3. 枚举的 valueOf / values
# -----------------------------------------------------------------------------
# `enumValueOf<T>("...")` 与 `valueOf` 走反射,枚举被改名后传字符串就崩。
# 项目里枚举用得很多(ShiftMode / Layer / Kind / ShiftMode 档位…),这条是高频崩溃源。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------------------------
# 4. 自定义 View
# -----------------------------------------------------------------------------
# XML 布局里写了全类名(`<view class="...">` / `android:name`), inflater 反射构造。
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# -----------------------------------------------------------------------------
# 5. Kotlin 协程
# -----------------------------------------------------------------------------
# `DebugProbes` 之类不在 release 路径上,但 kotlinx-coroutines-android 引用了部分内部类。
-dontwarn kotlinx.coroutines.**

# -----------------------------------------------------------------------------
# 6. 本项目用到的反射点(逐条对应代码里的真实用法)
# -----------------------------------------------------------------------------
# `KeyIdentity` / `KeyLayout` 的层解析走字符串查表(`L4` / `L5` 段)。
-keep class dev.hwkbd.ime.keymap.** { *; }
# 用户词库 JSON 往返:字段名直接对应数据格式,改了就读不出老词典
-keep class dev.hwkbd.ime.engine.UserDict { *; }
# `StatusRow` 与状态行 XML 的五项对应(见 ime_root.xml 的 comment)
-keep class dev.hwkbd.ime.ui.StatusRow { *; }

# -----------------------------------------------------------------------------
# 7. 不确定的签名要保留 —— 崩溃栈里得有方法名才可能定位
# -----------------------------------------------------------------------------
# 注意:这与「混淆」的目标相反,但只针对极少数内部类,成本远小于失去可调试性。
-keepattributes SourceFile,LineNumber
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# 8. 去掉不该出现在包里的东西
# -----------------------------------------------------------------------------
# 日志与断言:`BuildConfig.DEBUG` 已被 R8 折掉,剩下的直接删。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
# Kotlin 的内部符号表与协程调试信息
-dontnote kotlin.**


# =============================================================================
# ⚠ 下面两条**故意没写**,说明为什么
# =============================================================================
#
# -keepattributes *  /  -keep class ** { *; }
#   等价于不混淆。R8 默认就会保留 InnerClasses/EnclosingMethod/Signature,
#   而本项目**没有**依赖它们做反射的地方(唯一的反射是枚举 valueOf,上面已单列)。
#   全局 keep 会把整个加固抵消掉,所以不加。
#
# -dontobfuscate
#   同上。目的是混淆,不是保命。
#
# 如果将来加了依赖反射的库(Jackson / Gson / Room),要在这里**按那个库**加规则,
# 而不是整体退回不混淆 —— 后者等于没开这个开关。

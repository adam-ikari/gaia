// librime 的 JNI 桥。走 `rime_get_api()` 函数指针表(见 rime_api.h 的 `struct RimeApi`)。
//
// ## 为什么是表而不是 `RimeXxx()`
//
// librime 1.x 公开的 C API **只有 `rime_get_api()` 一个符号**;`RimeSetup` /
// `RimeCreateSession` 那些名字在 `rime_api_impl.h` 里是 `namespace rime` 下的 C++ 函数
// (符号形如 `_Z12RimeFinalizev`),不是 C 链接,直接调连链接都过不去。
//
// 表设计的代价是**每个函数调用前都要判空** —— 表允许不同版本增删成员
// (`get_status` 就是后加的),所以下面统一用一个宏。
//
// ## 与 Rust 侧工程的对应
//
// 这是 `term-ime`(TTY 中文拼音输入法)的 Android 对应物。librime 的构建配方
// (改用 std::regex 而非 boost::regex、vendored deps 分两段编、leveldb 不带 snappy)
// 与那个项目同源,逐条理由见同目录 CMakeLists.txt。

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>

#include "rime_api.h"

// 「这个候选吃掉了几个输入字符」只能从 C++ 侧拿,`RimeApi` 表里没有这一项(见
// nativeSelectCandidate 的注释)。librime 是**静态链接**进来的(见 CMakeLists.txt),
// 它的头文件与实现都在同一个 so 里,所以这里可以直接用 C++ API。
#include <rime/service.h>   // Service::instance() / Session::context()
#include <rime/context.h>   // Context::composition() / GetSelectedCandidate()
#include <rime/composition.h>  // Composition::GetCurrentStartPosition()
#include <rime/candidate.h>    // Candidate::preedit() / start() / end()

namespace {

const RimeApi* api() {
  static const RimeApi* cached = rime_get_api();
  return cached;
}

// 单实例。全局状态,rime 本身也是单实例模型(一个 user_data 目录)。
struct Engine {
  RimeTraits traits;
  RimeSessionId session = 0;
  bool initialized = false;
  bool deployed = false;
};

Engine g_engine;

// librime 要求的日志回调。它没有默认实现,不装的话日志走 printf。
void rime_log(const char* message, int code) {
  __android_log_print(code >= 1 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "Rime", "%s",
                      message ? message : "");
}

std::string to_std(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}

// 取 session 的 context。**必须用 RIME_STRUCT_INIT 初始化 data_size** ——
// librime 用它判断结构体版本,不设会被当成老版本,后面的成员全部错位。
// 症状是"能取到 context 但字段是垃圾",比直接返回 false 难查得多。
bool context_of(const RimeApi* a, RimeSessionId sid, RimeContext* ctx) {
  if (!a || !a->get_context || !sid || !ctx) return false;
  RIME_STRUCT_INIT(RimeContext, *ctx);
  return a->get_context(sid, ctx) ? true : false;
}

}  // namespace

extern "C" {

// 每个入口的第二个参数写成 `jobject` 而不是 `jclass`,是**照实**而不是讲究:
// Kotlin `object` 里的 `private external fun` 编出来是**实例方法**,而 JNI 对
// 实例方法传的是 `jobject`(静态方法才传 `jclass`)。
// 两种情况下它都只是「那个类的引用」,而下面这些函数**从不解引用它**
// —— librime 的句柄全在第一个参数 `jlong session` 里。
// 所以这个名字对行为没有任何影响,写对了是为了下一个读它的人不误判。

JNIEXPORT jstring JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeVersion(JNIEnv* env, jobject) {
  const RimeApi* a = api();
  if (!a || !a->get_version) return env->NewStringUTF("(no rime api)");
  const char* v = a->get_version();
  return env->NewStringUTF(v ? v : "(null)");
}

JNIEXPORT jstring JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeApiProbe(JNIEnv* env, jobject) {
  const RimeApi* a = api();
  if (!a) return env->NewStringUTF("api=NULL");
  int present = 0, total = 0;
  auto check = [&](bool b) { ++total; if (b) ++present; };
  check(a->get_version);   check(a->setup);         check(a->initialize);
  check(a->finalize);      check(a->start_maintenance); check(a->join_maintenance_thread);
  check(a->create_session); check(a->process_key);   check(a->get_context);
  check(a->get_commit);    check(a->get_context);  check(a->clear_composition);
  check(a->get_status);    check(a->get_user_id);   check(a->select_candidate);
  check(a->commit_composition); check(a->process_key);
  char buf[64];
  snprintf(buf, sizeof(buf), "api=OK present=%d/%d", present, total);
  return env->NewStringUTF(buf);
}

/**
 * 初始化:装日志回调 → setup(指定 user/shared 数据目录)→ initialize → 部署共享数据。
 *
 * 部署那一步(shared data → *.bin)是**最慢**的,首次可能几秒到几十秒。
 * 所以它不在这里同步做,而是走 `nativeDeployAsync` + 轮询 —— 输入法启动路径上
 * 不能等它。
 *
 * @return 0 = 成功,非 0 = 失败(错误码见 nativeLastError)
 */
JNIEXPORT jint JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeInit(
    JNIEnv* env, jobject, jstring userDir, jstring sharedDir, jstring appDataDir,
    jstring schemaId, jstring appId) {
  const RimeApi* a = api();
  if (!a || !a->setup || !a->initialize || !a->start_maintenance) return -1;

  const std::string user = to_std(env, userDir);
  const std::string shared = to_std(env, sharedDir);
  const std::string app_data = to_std(env, appDataDir);
  const std::string schema = to_std(env, schemaId);
  const std::string app_id = to_std(env, appId);

  if (g_engine.initialized) {
    if (a->finalize) a->finalize();
    g_engine = Engine{};
  }

  // 目录、app_name、distribution_* 全都装在 **RimeTraits** 里 ——
  // `RimeApi` 上**没有** `set_user_data_dir` 之类的方法(编译报 "no member named
  // 'set_user_data_dir' in 'rime_api_t'")。traits 必须在 setup 之前填好,
  // 因为 setup 之后 initialize 会直接按它去读词典。
  //
  // 注意 `data_size` 必填:这是 librime 判断结构体版本的字段,不填它 initialize
  // 会当成 v0.8 老结构体,后面的字段全部错位 —— 症状是能初始化但一查就崩。
  g_engine.traits.data_size = sizeof(RimeTraits);
  g_engine.traits.shared_data_dir = shared.c_str();
  g_engine.traits.user_data_dir = user.c_str();
  // prebuilt/staging 有默认值(${shared}/build 与 ${user}/build),但**必须活过
  // initialize 的整个调用**,所以不能指向临时字符串 —— 上面三个都是 std::string 的
  // 成员,在这个函数里活到 return,安全。
  g_engine.traits.prebuilt_data_dir = app_data.c_str();
  g_engine.traits.staging_dir = app_data.c_str();
  // app_name 带 "rime." 前缀(见 rime_api.h 的说明),旧的 log 文件会自动被清理。
  // 它同时是**用户空间的标识**,改了等于换用户空间(已学到的用户词会"消失"),
  // 所以定下来就别动。
  g_engine.traits.app_name = schema.empty() ? "rime.hwkbd" : "rime.hwkbd";
  g_engine.traits.distribution_name = "hwkbd-ime";
  g_engine.traits.distribution_version = "0.0.0";
  // 日志走 stderr 太吵;min_log_level=2 只留 ERROR/WARNING。
  g_engine.traits.min_log_level = 2;

  a->setup(&g_engine.traits);
  a->initialize(&g_engine.traits);
  g_engine.initialized = true;

  // 异步起部署:maintenance 在后台线程跑,这里只发起。
  a->start_maintenance(0 /* incremental */);
  return 0;
}

/** 等部署线程结束。第一次部署慢,这里会阻塞,所以只在后台线程调。 */
JNIEXPORT void JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeJoinMaintenance(JNIEnv*, jobject) {
  const RimeApi* a = api();
  if (a && a->join_maintenance_thread) a->join_maintenance_thread();
  g_engine.deployed = true;
}

/** 打开一个 session。候选、commit 都挂在 session 上,所以要先有它。 */
JNIEXPORT jlong JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeCreateSession(JNIEnv*, jobject) {
  const RimeApi* a = api();
  if (!a || !a->create_session) return 0;
  RimeSessionId id = a->create_session();
  if (id) g_engine.session = id;
  return static_cast<jlong>(id);
}

JNIEXPORT void JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeDestroySession(
    JNIEnv*, jobject, jlong session) {
  const RimeApi* a = api();
  if (a && a->destroy_session && session) a->destroy_session(static_cast<RimeSessionId>(session));
}

JNIEXPORT void JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeDestroy(JNIEnv*, jobject) {
  const RimeApi* a = api();
  if (g_engine.initialized && a && a->finalize) a->finalize();
  g_engine = Engine{};
}

/**
 * 清空当前输入(等价于「用户按了 ESC / 切语言」)。
 *
 * **不能靠发退格**:librime 的退格要经 `process_key` 走完整状态机,而我们要的
 * 是「一次性把 composition 清掉」。`get_context` 的 `clear()` 是对的做法。
 */
JNIEXPORT void JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeReset(JNIEnv*, jobject, jlong session) {
  const RimeApi* a = api();
  if (!a || !a->clear_composition || !session) return;
  a->clear_composition(static_cast<RimeSessionId>(session));
}

/**
 * 候选词列表 —— **扁平**返回 `String[]`。
 *
 * ## 为什么用 `RimeContext.menu.candidates[]` 而不是迭代器
 *
 * 两条路都能取到。选 menu 是因为它一次给全:`num_candidates` 是本页数量、
 * `candidates[]` 是数组、`highlighted_candidate_index` 顺带就有 ——
 * 而迭代器(`candidate_list_begin/next/end`)**每个词要调一次 native**,
 * 一页 10 个词就是 10 次 JNI 往返,而输入法里这是**每个按键**都会走的路径。
 *
 * 迭代器唯一的优势(自己控制页大小)在 menu 这里拿不到:`RimeMenu` 有 `page_size` /
 * `page_no`,但那是**读**当前方案的配置,不能设。
 *
 * ## 所以「要更多候选」的正确做法是改方案,不是在这里翻页
 *
 * 上面那句「上层要更多就得翻页」曾经被读成「上层自己去调 `change_page`」。
 * **那样会把翻页功能整个废掉**,而且症状极隐蔽(2026-10-03 E2E 实测):
 *
 * - `page_size` 不写就是 librime 默认的 **5**(见 `src/rime/schema.cc`);
 * - 于是每次按键最多拿 5 个候选 ⇒ `ImeState.totalPages` 恒为 1 ⇒
 *   `CandidateBarView.pageNextSlots/pagePrevSlots` 恒空 ⇒ 左右滑永远是「到头」;
 * - 而界面其它一切(候选、飞字、空格上屏)**全都正常**,只有翻页不发生。
 *
 * 现在的约定:`assets/rime/luna_pinyin.schema.yaml` 里写
 * `menu: page_size: 45`(与 `ImeController.CANDIDATE_LIMIT` 一致),
 * 于是 `candidates[]` 一次给全,本项目自己那份扁平列表就是**完整候选集**。
 *
 * **不要在这里用 `change_page` 翻 librime 的页码** —— 本项目的 `query()`
 * 是无状态的(每次 `clear_composition()` + 逐字重喂),引入第二份页码状态
 * 就会和 `state.raw` 漂移,而漂移出来的 bug 复现不了。
 *
 * @return 候选词数组;空数组表示没候选(不是 null)
 */
JNIEXPORT jobjectArray JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeCandidates(
    JNIEnv* env, jobject, jlong session, jint limit) {
  const RimeApi* a = api();
  jclass str_cls = env->FindClass("java/lang/String");
  RimeContext ctx;
  if (!context_of(a, static_cast<RimeSessionId>(session), &ctx)) {
    return env->NewObjectArray(0, str_cls, nullptr);
  }
  const RimeMenu& menu = ctx.menu;
  if (!menu.candidates || menu.num_candidates <= 0) {
    return env->NewObjectArray(0, str_cls, nullptr);
  }
  int n = menu.num_candidates;
  // 上限有两个:调用方给的 limit,以及一个硬顶。
  // 硬顶防的是**方案配错**(page_size 写成几百)时一次吐出巨大数组 —— 那会直接
  // 在输入法主线程上分配几百 KB 并逐个 NewStringUTF。
  //
  // 注意这两个上限是**静默截断**,不报错。所以它们必须与方案里的 `page_size`
  // 一起看:schema 给 45、这里 limit 45 → 相等,不会截。
  // 万一哪天有人只改了其中一头,症状是「候选数莫名少一截」而不是报错。
  const int cap = limit > 0 ? std::min<int>(limit, 100) : 100;
  if (n > cap) n = cap;
  // 截断发生了就留下痕迹:这一行是唯一能看出「方案给的比我们要的多」的地方。
  if (menu.num_candidates > n) {
    __android_log_print(ANDROID_LOG_WARN, "RimeNative",
                        "candidates truncated: got %d, cap %d "
                        "(schema menu/page_size 与调用方 limit 不一致)",
                        menu.num_candidates, n);
  }

  std::vector<std::string> out;
  out.reserve(static_cast<size_t>(n));
  for (int i = 0; i < n; ++i) {
    const char* t = menu.candidates[i].text;
    if (t) out.emplace_back(t);
  }
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(out.size()), str_cls, nullptr);
  for (size_t i = 0; i < out.size(); ++i) {
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(out[i].c_str()));
  }
  return arr;
}

/**
 * 菜单第一项(候选栏中央槽、空格键选中那个)候选的**音节拼写**与它覆盖的输入区间。
 *
 * ## 这东西干什么用
 *
 * 拼音显示要「自动加入分隔符」:引擎知道首选候选怎么切音节
 * (`ScriptSyllabifier::GetPreeditString` 用 schema 的 delimiter 拼音节,
 * 见 `script_translator.cc` 的 `ScriptTranslation::Peek`),上层只需要把它
 * 取出来显示。C API 的 `RimeCandidate` 没有 preedit 字段(只有 text/comment),
 * 所以走 C++:`Context::GetSelectedCandidate()` 拿当前段高亮候选,
 * 它正是候选栏中央那一格(菜单第一项 = li=0)。
 *
 * ## 返回格式
 *
 * `String[3]` = `[preedit, start, end]`;没有候选/取不到 ⇒ **空数组**
 * (空表比垃圾数据安全,调用方据此回退到原始拼写)。
 *
 * - [preedit]:音节拼写,**空格分节**(`GetPreeditString` 取
 *   `delimiters().at(0)`,本 schema 的 `speller/delimiter` = " '",第一个字符是空格)。
 *   换成项目约定的 `'` 是 Kotlin 侧的事 —— 显示规范不属于引擎层。
 * - [start] / [end]:候选覆盖的**输入区间**,全输入坐标。注意 `RimeEngine.query`
 *   重建时把 `'` 跳过了(它不喂引擎),所以这是**不含撇号**的坐标 ——
 *   与 `selectAndRemain` 返回的 `consumed` 同一坐标系,映射回 raw 要数非撇字符。
 */
JNIEXPORT jobjectArray JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeTopSpelling(
    JNIEnv* env, jobject, jlong session) {
  jclass str_cls = env->FindClass("java/lang/String");
  jobjectArray empty = env->NewObjectArray(0, str_cls, nullptr);
  if (!session) return empty;
  try {
    auto s = rime::Service::instance().GetSession(static_cast<RimeSessionId>(session));
    if (!s) return empty;
    auto* ctx = s->context();
    if (!ctx) return empty;
    auto cand = ctx->GetSelectedCandidate();
    if (!cand) return empty;
    // start/end 是输入坐标(preedit 覆盖的那段)。GetSelectedCandidate 在
    // 没有段时返回 null,所以这里 cand->start()/end() 一定可读。
    const std::string preedit = cand->preedit();
    const std::string start = std::to_string(cand->start());
    const std::string end = std::to_string(cand->end());
    jobjectArray out = env->NewObjectArray(3, str_cls, nullptr);
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(preedit.c_str()));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(start.c_str()));
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(end.c_str()));
    return out;
  } catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_WARN, "RimeNative",
                        "nativeTopSpelling failed: %s", e.what());
    return empty;
  } catch (...) {
    return empty;
  }
}

/**
 * 当前 composition 长度(已输入的音节数)。
 *
 * 上层要知道「还剩多少没上屏」来决定是否显示候选栏,所以要这个。
 * Rime 的 `context.length` 是**字节数**,对中文输入不直观,这里返回字节数原值,
 * 由 Kotlin 侧判断。
 */
JNIEXPORT jint JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeCompositionLength(
    JNIEnv*, jobject, jlong session) {
  RimeContext ctx;
  if (!context_of(api(), static_cast<RimeSessionId>(session), &ctx)) return 0;
  return ctx.composition.length;
}

/**
 * 上屏第 [index] 个候选,返回**这次选词吃掉了几个输入字符**。
 *
 * [index] 是**菜单里的全局下标**(对应 `nativeCandidates` 的下标),不是页内下标。
 *
 * ## 为什么返回值是「吃掉几个输入字符」,而不是「剩下的文本」
 *
 * 上一版这里返回 `Bool`,由上层拿 `commit_text_preview` 去减 —— **那条路是错的**,
 * 而且是那种「跑得通、看着有模有样、结果全错」的错误。2026-10-05 真机日志实测:
 *
 *     raw="nihao"  选中第 4 个候选「你」(它只覆盖 "ni")
 *     commit_text_preview = "你好"
 *     ⇒ 上层剥掉「你」得到「好」,把「好」的长度 1 当成拼音长度
 *       ⇒ 缓冲从 "nihao" 变成 "n"(应该是 "hao")
 *
 * 错在 `Composition::GetCommitText()`(src/rime/composition.cc):
 *
 * ```cpp
 * for (const Segment& seg : *this) {
 *   if (auto cand = seg.GetSelectedCandidate()) { result += cand->text(); }
 *   else { result += input_.substr(seg.start, seg.end - seg.start); }  // 几乎走不到
 * }
 * ```
 *
 * 那个 `else` 分支要 `GetSelectedCandidate()` 返回 nullptr 才进得去,而
 * `Segment::selected_index` **默认值就是 0**(src/rime/segmentation.h),
 * 于是**任何一个有菜单的段都会返回它的第 0 个候选**,永远不会 nullptr。
 *
 * ⇒ `commit_text_preview` 是**「现在按回车会提交什么」的预览**(每段都填候选文字),
 *   根本不是「哪些输入被吃掉了」的记录。拿它反推长度必然错,
 *   而且错得没有任何征兆:单音节时答案恰好是对的。
 *
 * ## 正确的来源:composition 当前段的起点
 *
 * `Select` 之后 librime 会走 `ConcreteEngine::OnSelect` → `composition().Forward()`,
 * **追加一个起点 = 刚被吃掉那段末尾的空段**。于是
 * `Composition::GetCurrentStartPosition()`(就是 `back().start`)就是被吃掉的输入长度:
 *
 * - 选「你」(覆盖 "ni")⇒ 当前段从下标 2 起 ⇒ 返回 2 ⇒ 剩 "hao"
 * - 选「你好」(覆盖全部)⇒ 返回 0,见下
 *
 * ## **全部吃掉时返回 0,不是长度** —— 原因在 `Forward()` 的 early return
 *
 * `ConcreteEngine::OnSelect`(src/rime/engine.cc)在 `seg.end == input.length()`
 * 时走 `composition().Forward()`。而 `Segmentation::Forward()`
 * (src/rime/segmentation.cc)的头一句是:
 *
 * ```cpp
 * if (empty() || back().start == back().end) return false;   // ← 早退
 * push_back(Segment(back().end, back().end));
 * ```
 *
 * 单音节输入时 `back()` 就是**那个已确认段本身**(它的 start≠end,本该 push),
 * 但注意 `OnSelect` 之前 `seg.Close()` 已把段收到 `cand->end()`;
 * 对 `ni` 选「妳」而言 `Close()` 之后 start=0/end=2 —— 仍不相等,于是 push 出
 * `Segment(2,2)`,`GetCurrentStartPosition()` 应当是 **2**。
 *
 * **实测却是 0**,而 `commit_text_preview` 同时是 **null**(真机 logcat,2026-10-05):
 *
 * ```
 * consume: index=0 word="妳" consumed=0 preview="null"
 * ```
 *
 * `preview == null` 是关键证据:`RimeGetContext` 只在 `ctx->IsComposing()` 时填它
 * (rime_api_impl.h:220),而 `IsComposing()` 是 `!input_.empty() || !composition_.empty()`。
 * 两者皆空 ⇒ **整个 composition 被清掉了**,不只是「只剩一个空段」——
 * 也就是这一步之后走的是 `Context::Commit()`/`Clear()` 那条路,
 * 而本项目并没有配置 `_auto_commit`(见 `RimeNative.initialize` 传的 schema),
 * 具体由哪个 processor 触发未逐行追到底。
 *
 * **结论不变**:全吃掉 ⇒ 返回 0 ⇒ 上层当「说不清,照旧全清」——
 * 而「全清」正是全吃掉时的正确答案,所以这条路径的行为是对的。
 * 只是**别把 0 读成「什么都没吃掉」**。
 *
 * ⇒ 上层因此把 **`consumed <= 0` 一律当「说不清」**(见 RimeEngine.selectAndRemain),
 * 不去区分 0 与 -1:前者是「全清」,后者是「没选成」,两者上层动作相同。
 *
 * @return 被吃掉的输入字符数(>= 0);**-1 = 没选成**(index 越界 / 没 session)
 */
JNIEXPORT jint JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeSelectCandidate(
    JNIEnv*, jobject, jlong session, jint index) {
  const RimeApi* a = api();
  if (!a || !a->select_candidate || !session || index < 0) return -1;
  auto sid = static_cast<RimeSessionId>(session);
  if (!a->select_candidate(sid, static_cast<size_t>(index))) return -1;

  rime::an<rime::Session> s = rime::Service::instance().GetSession(sid);
  if (!s) return -1;
  rime::Context* ctx = s->context();
  if (!ctx) return -1;
  return static_cast<jint>(ctx->composition().GetCurrentStartPosition());
}

/**
 * 选出候选之后的 `commit_text_preview` —— **只用于埋点,不能用来算消费**。
 *
 * ## 它是什么
 *
 * `Composition::GetCommitText()` 的产物:**「现在按回车会提交什么」的预览** ——
 * 每个段都填**候选文字**,连还没被选中的段也是(见上面 `nativeSelectCandidate`
 * 里 `selected_index` 默认 0 那段分析)。
 *
 * `nihao` 选「你」(只覆盖 "ni")之后它是 `"你好"`:
 * 「你」是被吃掉的那段的候选文字,「好」是**剩下那段当前高亮的候选**。
 *
 * ## 为什么不能拿它反推「还剩多少拼音」
 *
 * 上一版(2026-10-04)的整条「按段消费」就是靠它:把刚选中的词从 preview 里剥掉,
 * 剩下的当拼音。**这个假设是错的** —— 剩下的那部分是**汉字**,不是拼音,
 * 拿它的长度当拼音长度就会把缓冲截到错误的位置(实测 `nihao` → `n`,见上)。
 *
 * 真正的答案在 `nativeSelectCandidate` 的返回值里。这个入口留着是因为
 * 它是**唯一能一眼看出「引擎认为的整串是什么」的字段** ——
 * 上面那个 bug 正是靠对比它与 `select:` 那行埋点才定位到的。
 *
 * ## 为什么这个字段一定被填上
 *
 * `RimeGetContext` 只在 `ctx->IsComposing()` 时填它。而选中之后
 * `composition_.Forward()` 只是**追加**一个空段,composition 仍非空 ⇒ 一定填。
 *
 * @return `commit_text_preview`;拿不到时返回 null(**空串是合法值**,别混)
 */
JNIEXPORT jstring JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeCommitTextPreview(
    JNIEnv* env, jobject, jlong session) {
  RimeContext ctx;
  if (!context_of(api(), static_cast<RimeSessionId>(session), &ctx)) return nullptr;
  const char* p = ctx.commit_text_preview;
  if (!p) return nullptr;   // 空串在这里表示「确实什么都没有」,要如实返回
  return env->NewStringUTF(p);
}

/**
 * 选出后取 commit 文本。
 *
 * **必须区分「有 commit」与「没 commit」**:没 commit 时 `commit_text` 是 null 且
 * `commit_text_len` 为 0,而空字符串是合法的 commit(某些方案会产出空)。
 * 用长度判断会把后者丢掉。
 */
JNIEXPORT jstring JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeCommit(
    JNIEnv* env, jobject, jlong session) {
  const RimeApi* a = api();
  if (!a || !a->get_commit || !session) return nullptr;
  RimeCommit c;
  std::memset(&c, 0, sizeof(c));
  a->get_commit(static_cast<RimeSessionId>(session), &c);
  if (!c.text) return nullptr;
  jstring r = env->NewStringUTF(c.text);
  if (c.text) free(const_cast<char*>(c.text)); // librime 分配的,调用方负责释放
  return r;
}

/** 候选栏高亮到第几项(-1 = 无高亮)。UI 要用它给选中项加背景。 */
JNIEXPORT jint JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeHighlighted(
    JNIEnv*, jobject, jlong session) {
  RimeContext ctx;
  if (!context_of(api(), static_cast<RimeSessionId>(session), &ctx)) return -1;
  return ctx.menu.highlighted_candidate_index;
}

/**
 * 喂一个按键给 session。
 *
 * ## 为什么要「清空后逐字重喂」而不是只喂新增的那一个
 *
 * librime 的 session 是**有状态机**:喂什么它就沿着走,退格、选词、切换都改内部状态。
 * 而上层([ImeController])的状态是 `state.raw` —— 一个**累积的拼音串**,是唯一真相源。
 * 两者对不上:用户可能按退格、可能切语言、可能从候选栏选了词,这时 librime 的内部状态
 * 与 `state.raw` 已经不同步了。
 *
 * 与其去同步两份状态(必然会有漏),不如**每次查询都以 `state.raw` 为准重建**:
 * `clear_composition` 然后逐字 `process_key`。n ≤ 约 20 个字母,而 `query` 每个按键
 * 才走一次,代价可以忽略;换来的是"librime 看到的状态永远等于 state.raw"。
 *
 * @param keycode 字符的 ASCII 码。librime 对 ASCII 直接用字符码当 keycode。
 * @return librime 是否消费了这个键
 */
JNIEXPORT jboolean JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeProcessKey(
    JNIEnv*, jobject, jlong session, jint keycode) {
  const RimeApi* a = api();
  if (!a || !a->process_key || !session) return JNI_FALSE;
  return a->process_key(static_cast<RimeSessionId>(session), static_cast<int>(keycode), 0)
             ? JNI_TRUE
             : JNI_FALSE;
}

}  // extern "C"

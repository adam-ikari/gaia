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

/** 上屏指定序号的候选(按 0 起算,对应 `nativeCandidates` 的下标)。 */
JNIEXPORT jboolean JNICALL Java_dev_hwkbd_ime_rime_RimeNative_nativeSelectCandidate(
    JNIEnv*, jobject, jlong session, jint index) {
  const RimeApi* a = api();
  if (!a || !a->select_candidate || !session || index < 0) return JNI_FALSE;
  return a->select_candidate(static_cast<RimeSessionId>(session), static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

/**
 * 选出候选之后的 **`commit_text_preview`**(2026-10-04 修:上一版取错了字段)。
 *
 * ## 上一版为什么一次都没生效
 *
 * 上一版读的是 `composition.preedit`,**那个字段也给不出「剩下的拼音」**:
 * librime 的 `OnSelect`(src/rime/engine.cc)在段尾时走的是
 * `composition().Forward()` 而不是 `Commit()`,所以 commit 队列里什么都没有 ——
 * 于是 `RimeNative.commit()` 恒为 null,整条「按段消费」路径**一次都没走到**。
 * 用户看到的仍然是「一次清空」。
 *
 * 而且即便 commit 队列有东西,`preedit` 也不是答案:它是
 * `Composition::GetPreedit()`,对**已确认**的段会填**候选文字**。
 * `nihao` 选「好」之后 preedit 是 `"ni好"` —— 里面已经混进了被吃掉的那段。
 *
 * ## 正确的来源是 `commit_text_preview`
 *
 * `Composition::GetCommitText()` 逐段走:已选中的段填**候选文字**,
 * 没选中的段填**原始输入**。所以 `nihao` 选「好」之后它是 `"ni好"` ——
 * **末尾那个词就是刚被吃掉的,其余就是没被吃掉的**。
 * 上层拿已知的候选词把那段剥掉即可(见 `unconsumedFromPreview`)。
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

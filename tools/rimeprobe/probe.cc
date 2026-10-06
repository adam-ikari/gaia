// librime 宿主机探针 —— 用来在**没有设备**的情况下回答关于选词/分段的问题。
//
// ## 为什么需要它
//
// 这个项目的真机日志通道是**断的**:用户那台设备没有 adb、给不了 logcat,
// 而本机没有 /dev/kvm 所以模拟器自己就 SystemUI ANR。于是「源码推导」成了唯一的
// 依据 —— 而源码推导已经骗过人一次(见 brain 页 `rime-candidate-consumption`:
// 我因为只读 `Context::Select` 的函数体,就把一个源码完全支持的结论推翻并改了标题)。
//
// librime 是 vendored 的(依赖全在 `deps/`),所以**它能在宿主机上编起来跑**。
// 这个探针就是那条缺失的证据通道。
//
// ## 它回答什么
//
// 逐个候选回答:「在 `<input>` 上选中菜单第 `i` 个候选之后,
//   1. `Composition::GetCurrentStartPosition()` 到底是多少(= 上层的 `consumed`)
//   2. composition 变成什么样(几段、每段 start/end/status)
//   3. 新的菜单里有没有剩余拼音的候选
//
// 这正是 `app/src/main/cpp/rime_jni.cpp` 的 `nativeSelectCandidate` 与
// `app/src/main/java/dev/hwkbd/ime/rime/RimeEngine.kt` 的 `currentCandidates`
// 在真机上走的那条路径 —— 同一个 schema、同一份词典、同一个 librime 源码。
//
// ## 用法
//
//     tools/rimeprobe/run.sh nihao "ni'hao" xian n
//
// run.sh 负责把 `app/src/main/assets/rime` 复制成一份 shared_data_dir、编依赖、
// 首次部署,再把参数原样转交。

#include <rime_api.h>

#include <rime/composition.h>
#include <rime/context.h>
#include <rime/segmentation.h>
#include <rime/service.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

const RimeApi* api() { return rime_get_api(); }

std::string g_shared;
std::string g_user;

const char* statusName(rime::Segment::Status s) {
  switch (s) {
    case rime::Segment::kVoid: return "kVoid";
    case rime::Segment::kGuess: return "kGuess";
    case rime::Segment::kSelected: return "kSelected";
    case rime::Segment::kConfirmed: return "kConfirmed";
    default: return "?";
  }
}

void dumpComposition(const rime::an<rime::Session>& s, const char* tag) {
  auto* ctx = s->context();
  auto& comp = ctx->composition();
  printf("    [%s] nseg=%zu consumed(GetCurrentStartPosition)=%zu preedit=\"%s\"\n",
         tag, comp.size(), comp.GetCurrentStartPosition(),
         ctx->GetPreedit().text.c_str());
  for (size_t i = 0; i < comp.size(); ++i) {
    const auto& seg = comp[i];
    printf("      seg[%zu] start=%zu end=%zu status=%s sel=%zu menu=%s\n", i, seg.start,
           seg.end, statusName(seg.status), seg.selected_index,
           seg.menu ? "有" : "无");
  }
}

void dumpMenu(RimeSessionId sid, int limit) {
  RimeContext ctx;
  // **必须** RIME_STRUCT_INIT:librime 用 data_size 判结构体版本,memset 成 0
  // 会被当成老版本,后面的成员全部错位(症状:取得到 context 但 menu 是空的)。
  RIME_STRUCT_INIT(RimeContext, ctx);
  api()->get_context(sid, &ctx);
  printf("    menu: n=%d page=%d(page_size=%d last=%d) highlight=%d\n",
         ctx.menu.num_candidates, ctx.menu.page_no, ctx.menu.page_size,
         ctx.menu.is_last_page ? 1 : 0, ctx.menu.highlighted_candidate_index);
  int n = ctx.menu.num_candidates < limit ? ctx.menu.num_candidates : limit;
  for (int i = 0; i < n; ++i) {
    printf("      [%d] %s\n", i,
           ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "(null)");
  }
  printf("      preedit=\"%s\" commit_text_preview=%s\n",
         ctx.composition.preedit ? ctx.composition.preedit : "(null)",
         ctx.commit_text_preview ? ctx.commit_text_preview : "(null)");
  api()->free_context(&ctx);
}

// 打一段输入,返回新建的 session(每个用例独立 session,避免 composition 串味)。
RimeSessionId feed(const std::string& input) {
  RimeSessionId sid = api()->create_session();
  if (!sid) {
    printf("!! create_session 失败\n");
    exit(2);
  }
  if (!input.empty()) api()->simulate_key_sequence(sid, input.c_str());
  return sid;
}

void probeOne(const std::string& input, int tryCandidates) {
  printf("\n================ input = %s ================\n", input.c_str());

  {
    RimeSessionId sid = feed(input);
    dumpComposition(rime::Service::instance().GetSession(sid), "输入后");
    dumpMenu(sid, tryCandidates);
    api()->destroy_session(sid);
  }

  for (int i = 0; i < tryCandidates; ++i) {
    printf("  --- 选中菜单第 %d 个 ---\n", i);
    RimeSessionId sid = feed(input);

    // 先记下这一轮**当时**的菜单 —— 候选排序受用户词频影响,每轮都可能不同,
    // 所以「第 i 个」在不同轮里未必是同一个词。打印出来才能对上。
    RimeContext pre;
    RIME_STRUCT_INIT(RimeContext, pre);
    api()->get_context(sid, &pre);
    std::string word;
    if (pre.menu.candidates && i < pre.menu.num_candidates &&
        pre.menu.candidates[i].text) {
      word = pre.menu.candidates[i].text;
    }
    api()->free_context(&pre);

    rime::an<rime::Session> s = rime::Service::instance().GetSession(sid);
    if (!s) { api()->destroy_session(sid); continue; }
    bool ok = api()->select_candidate(sid, static_cast<size_t>(i)) != 0;
    printf("    select_candidate(%d) → %d,选中的是 \"%s\"\n", i, ok ? 1 : 0,
           word.c_str());
    if (!ok) {
      api()->destroy_session(sid);
      continue;
    }
    dumpComposition(s, "选中后");
    dumpMenu(sid, 5);
    api()->destroy_session(sid);
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 4) {
    printf(
        "用法: rimeprobe <shared_data_dir> <user_data_dir> <input>...\n"
        "例子: rimeprobe /tmp/shared /tmp/user nihao \"ni'hao\"\n");
    return 1;
  }
  g_shared = argv[1];
  g_user = argv[2];

  RimeTraits traits;
  std::memset(&traits, 0, sizeof(traits));
  traits.data_size = sizeof(RimeTraits);
  traits.shared_data_dir = g_shared.c_str();
  traits.user_data_dir = g_user.c_str();
  std::string build = g_user + "/build";
  traits.prebuilt_data_dir = build.c_str();
  traits.staging_dir = build.c_str();
  traits.app_name = "rime.hwkbd";
  traits.distribution_name = "hwkbd-ime";
  traits.distribution_version = "0.0.0";
  // 0 = 全部级别。真机上是 2(只留 ERROR/WARNING),探针要 DLOG 的分段轨迹。
  traits.min_log_level = 0;

  const RimeApi* a = api();
  a->setup(&traits);
  a->initialize(&traits);
  printf("initialize 完成,部署中...\n");
  a->start_maintenance(0);
  if (a->join_maintenance_thread) a->join_maintenance_thread();
  printf("部署完成。\n");

  int tryCandidates = 6;
  for (int i = 3; i < argc; ++i) probeOne(argv[i], tryCandidates);

  a->finalize();
  return 0;
}
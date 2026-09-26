---
slug: mindmap
title: Feature mindmap
role: feature mindmap
updated: "2026-09-23T04:35:28"
---

# Feature mindmap

```mermaid
mindmap
  root((hwkbd_ime<br/>Titan 2 全键盘输入法))
    物理键盘优先
      硬件按键直通管线
        空闲态原样放行 保证 Termux/vim 拿到原始按键
        组词态才消费按键
      三层键盘
        L1 基础字母层
        L2 符号数字层 Alt
        L3 编程开发层 Sym
          成对括号相邻 {} [] ()
          运算与注释 -> => // == != : ;
          功能键 ESC TAB F5 方向键
          每键可自定义输出
      锁定语义
        Alt 单击一次性 双击锁定 L2
        Sym 单击一次性 双击锁定 L3
        锁定态 Alt/Sym 互切层
        Sym+空格 三层循环
        按住 Sym = Ctrl 组合键
        Shift 单击大写 双击 Caps
      选字与翻页
        空格选首候选
        Ctrl(Fn)+q w e r t 选 1..5 候选
        Tab/DPAD 高亮 DPAD_UP 确认
        DPAD_LEFT RIGHT 翻页 来自翻屏助手
    拼音引擎
      前缀匹配候选
      DP 分词组词 智能组词首选
      用户词频记忆 长按删除
      内置中等词库 gzip TSV
      词库导入与回退
    屏幕手势
      候选栏点选
      候选栏上滑选字
      候选栏左右滑翻页
      软键盘 5 区上滑选字 屏幕版飞字
      软键盘左右滑翻页
    工程与适配
      状态机与引擎 JVM 单测
      按键探测页 抓 Sym/Fn 真实键值
      设置与层映射文本配置
      无网络权限 最小权限面
    二期
      双拼
      繁体
      联想
      无障碍快捷发送
```

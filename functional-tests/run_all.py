#!/usr/bin/env python3
"""功能回归统一入口：按顺序运行各套件并汇总。

用法：
    python3 run_all.py [BASE_URL]        # BASE_URL 默认 http://127.0.0.1:8088

环境门控：
    FUNCTIONAL_AI_LIVE=true   才会运行 AI 全链路套件（需要目标环境配置真实模型密钥）；
    默认跳过并在汇总中明确标注"该路径本轮未验证，不得视为通过"。

判定：
    任一套件失败 → 退出码 1；跳过不计为失败，但必须如实呈现。
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8088"

AI_LIVE = os.environ.get("FUNCTIONAL_AI_LIVE") == "true"

SUITES = [
    ("suite_core.py",
     "基础链路：简历/版本、PDF×7 模板、ATS 规则、投递、导入、跨账号隔离、边界码",
     True),
    ("suite_lifecycle.py",
     "全功能扩展：账号生命周期、资料/JD/版本归档恢复、评分、沟通模板 CRUD、面试全流程、答案资产、删号",
     True),
    ("suite_edges.py",
     "安全与边界：refresh 旋转、删除一致性、ATS ai-retry 语义、AI 门禁、aiFailure 暴露",
     True),
    ("suite_ai_full.py",
     "AI 全链路：7 类 AI 任务 + 授权撤回门禁（需目标环境配置真实模型密钥）",
     AI_LIVE),
]


def main():
    results = []
    for script, label, enabled in SUITES:
        if not enabled:
            print(f"\n=== [SKIP] {label} ===", flush=True)
            print("  原因：AI 套件需 FUNCTIONAL_AI_LIVE=true 且目标环境配置真实模型密钥。"
                  "该路径本轮未验证，不得视为通过。", flush=True)
            results.append((script, label, "SKIPPED"))
            continue
        print(f"\n=== [RUN ] {label} ===", flush=True)
        print(f"----- {script} -----"  , flush=True)
        proc = subprocess.run([sys.executable, "-u", os.path.join(HERE, "suites", script), BASE])
        status = "PASS" if proc.returncode == 0 else f"FAIL(exit={proc.returncode})"
        results.append((script, label, status))

    print("\n" + "=" * 70)
    print("功能回归汇总")
    print("=" * 70)
    failed = 0
    skipped = 0
    for script, label, status in results:
        print(f"  [{status:^7}] {label}")
        if status.startswith("FAIL"):
            failed += 1
        elif status == "SKIPPED":
            skipped += 1
    print("-" * 70)
    print(f"套件 {len(results)} 个：失败 {failed}，跳过 {skipped}，通过 {len(results) - failed - skipped}")
    if skipped:
        print(f"⚠ 有 {skipped} 个套件被跳过：对应路径本轮**未验证**，结论必须如实标注，不得宣称'全部功能已验证'。")
    print("=" * 70, flush=True)
    # CI 集成：若设置了 FUNCTIONAL_SUMMARY_FILE，把汇总块追加进去（供 $GITHUB_STEP_SUMMARY 使用）
    summary_file = os.environ.get("FUNCTIONAL_SUMMARY_FILE")
    if summary_file:
        try:
            with open(summary_file, "a", encoding="utf-8") as fh:
                fh.write("### 功能回归汇总\n\n")
                fh.write("| 套件 | 结果 |\n| --- | --- |\n")
                for script, label, status in results:
                    fh.write(f"| {script} | **{status}** — {label} |\n")
                fh.write(f"\n失败 {failed}，跳过 {skipped}。"
                         f"{('⚠ 跳过的套件对应路径本轮未验证。') if skipped else ''}\n")
        except OSError as exc:
            print(f"（无法写汇总文件 {summary_file}: {exc}）", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

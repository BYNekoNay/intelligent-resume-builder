#!/usr/bin/env python3
"""断言「某个测试类真的执行了」，而不只是 `mvn` 退出码为 0。

为什么需要它（本仓踩过两次，且都有记录）：
  用 `@EnabledIfEnvironmentVariable` 门控的测试类，在环境变量没生效时会被 JUnit **整类跳过**，
  而 `mvn test` 的退出码仍然是 **0**。只看退出码，就会把「未验证」记成「通过」。

  证据：`docs/RELEASES/v0.4.0.md` 记录过一次 `tests=1 skipped=1` 而 `mvn rc=0` 的情形；
  `scripts/persistence-gate.remote.sh` / `Invoke-MySql57MigrationGate.ps1` 都内置了同款判据。

用法：
  python3 scripts/assert-tests-ran.py <surefire-report.xml> <label>
  例：python3 scripts/assert-tests-ran.py \\
        server/target/surefire-reports/TEST-com.intelligentresume.database.MySql84FreshMigrationIT.xml \\
        "MySQL 8.4 fresh migration"

退出码：0 = 真实执行且通过；1 = 未执行 / 被跳过 / 有失败。
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path


def fail(message: str) -> int:
    print(f"GATE-RED {message}", file=sys.stderr)
    return 1


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    report = Path(argv[1])
    label = argv[2]

    if not report.exists():
        # 报告不存在 ≠ 通过：编译失败、-Dtest 名字写错、构建在更早阶段中断，都会走到这里
        return fail(f"[{label}] surefire 报告不存在：{report} —— 该类根本没被执行")

    root = ElementTree.parse(report).getroot()
    tests = int(root.get("tests", "0"))
    skipped = int(root.get("skipped", "0"))
    failures = int(root.get("failures", "0"))
    errors = int(root.get("errors", "0"))
    print(f"[{label}] surefire: tests={tests} skipped={skipped} failures={failures} errors={errors}")

    if tests == 0 or skipped >= tests:
        return fail(
            f"[{label}] 用例被 JUnit 跳过（环境变量门控未生效）—— "
            "这是「未验证」，不是「通过」。mvn 退出码为 0 也不代表门禁承重。"
        )
    if failures or errors:
        return fail(f"[{label}] 门禁失败：failures={failures} errors={errors}（详见 {report}）")

    print(f"GATE-GREEN [{label}] 用例真实执行且通过（{tests} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

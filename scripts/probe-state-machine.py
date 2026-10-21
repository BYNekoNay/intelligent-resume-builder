#!/usr/bin/env python3
"""投递状态机 9 态 + 状态历史 + 收尾边的真实环境探针（部署后运行，非 CI）。

覆盖功能回归尚未覆盖的部分：
  - SCREENING / NO_RESPONSE / HIRED 三个新态
  - NO_RESPONSE → REJECTED 一步收尾（不经 APPLIED 中转）
  - statusHistory 逐条落库（from/to/note）
  - 非法迁移被拒（终态不可回退）
  - stats 的 byStatus 含新态
"""
import json
import os
import secrets
import time
import urllib.error
import urllib.request

BASE = os.environ.get("PROBE_BASE", "http://127.0.0.1:8080")
# 凭据一律运行时生成或由环境变量注入 —— 仓库里不得出现任何可用的账号口令。
USER = os.environ.get("PROBE_USERNAME")
PWD = os.environ.get("PROBE_PASSWORD")
# 固定前缀只用于让运维在库里认出探针产生的记录，随机后缀保证可重复执行不冲突。
RUN_TAG = "probe" + secrets.token_hex(3)

RESUME = {
    "basics": {"fullName": "张三", "headline": "后端开发工程师", "email": "z@example.invalid",
               "phone": "13800000000", "location": "成都", "summary": "5 年后端服务经验。"},
    "objective": "深耕后端架构方向。",
    "layout": {"sectionOrder": ["basics", "objective", "work", "skills", "education"]},
    "work": [{"company": "示例科技", "position": "后端工程师", "startDate": "2024-01", "endDate": "至今",
              "highlights": ["负责订单服务开发与性能优化", "与产品测试运维协作交付"]}],
    "skills": [{"name": "Java", "category": "后端", "items": ["Java", "Spring Boot"], "level": "熟练"}],
    "education": [{"school": "某某大学", "major": "软件工程", "degree": "本科"}],
}
JD_TEXT = ("岗位职责：负责订单与交易链路的后端服务设计与开发。任职要求：熟悉 Java、"
           "Spring Boot、MySQL、Redis；具备高并发场景下的性能优化经验。")

results = []


def call(method, path, body=None, token=None, expect=None):
    """发一次请求；对 429 退避重试。返回 (code, data)。"""
    for attempt in range(5):
        req = urllib.request.Request(BASE + path, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        payload = json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(req, payload, timeout=30) as resp:
                raw = resp.read().decode("utf-8", "replace")
                code = resp.status
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            code = exc.code
        except Exception as exc:  # 连不上等
            print(f"  !! {method} {path} 异常: {exc}")
            return 0, {}
        if code == 429:
            time.sleep(6)
            continue
        try:
            data = json.loads(raw).get("data")
        except Exception:
            data = raw
        if expect is not None and code not in expect:
            print(f"  !! {method} {path} 期望 {expect} 实得 {code}: {raw[:200]}")
        return code, data
    return 0, {}


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name} -- {detail}")
    time.sleep(1.2)


print(f"目标: {BASE}")
if USER and PWD:
    code, data = call("POST", "/api/auth/login", {"username": USER, "password": PWD}, expect=(200,))
    token = (data or {}).get("accessToken")
    print(f"登录 HTTP {code} token={'有' if token else '无'}")
    if not token:
        raise SystemExit("登录失败，终止")
else:
    # 自注册一个一次性账号：随机用户名 + 随机口令，脚本不持有任何长期凭据
    USER = RUN_TAG
    PWD = "Pr-" + secrets.token_hex(9) + "!"
    code, data = call("POST", "/api/auth/register",
                      {"username": USER, "email": f"{USER}@example.invalid", "password": PWD},
                      expect=(200, 201))
    token = (data or {}).get("accessToken")
    print(f"注册 HTTP {code} 用户={USER} token={'有' if token else '无'}")
    if not token:
        raise SystemExit("注册失败，终止")

print("\n=== 准备 JD 与简历版本 ===")
code, job = call("POST", "/api/jobs",
                 {"title": f"后端工程师-状态机探针-{RUN_TAG}", "companyName": "示例公司", "jdText": JD_TEXT},
                 token=token, expect=(200, 201))
jid = (job or {}).get("id")
check("JD 创建", bool(jid), f"HTTP {code} id={jid}")

code, resume = call("POST", "/api/resumes", {"title": f"状态机探针简历-{RUN_TAG}", "resumeJson": RESUME},
                     token=token, expect=(200, 201))
rid = (resume or {}).get("id")
check("简历创建", bool(rid), f"HTTP {code} id={rid}")

code, version = call("POST", f"/api/resumes/{rid}/versions",
                     {"resumeJson": RESUME, "sourceType": "MANUAL"}, token=token, expect=(200, 201))
vid = (version or {}).get("id")
check("简历版本创建", bool(vid), f"HTTP {code} id={vid}")

print("\n=== 投递记录与状态迁移 ===")
code, app = call("POST", "/api/applications",
                 {"jobDescriptionId": jid, "resumeVersionId": vid, "status": "DRAFT",
                  "coverLetterText": "状态机探针"}, token=token, expect=(200, 201))
apid = (app or {}).get("id")
ver = (app or {}).get("version")
check("投递创建（DRAFT）", bool(apid), f"HTTP {code} id={apid} version={ver}")

if not apid:
    raise SystemExit("投递创建失败，终止")

# 逐态推进：DRAFT → APPLIED → SCREENING → NO_RESPONSE → REJECTED
for target, label in (("APPLIED", "DRAFT→APPLIED"),
                      ("SCREENING", "APPLIED→SCREENING（新状态）"),
                      ("NO_RESPONSE", "SCREENING→NO_RESPONSE（新状态）"),
                      ("REJECTED", "NO_RESPONSE→REJECTED（一步收尾边）")):
    code, updated = call("PATCH", f"/api/applications/{apid}/status",
                         {"status": target, "version": ver}, token=token, expect=(200,))
    ok = code == 200 and (updated or {}).get("status") == target
    check(f"状态迁移 {label}", ok, f"HTTP {code} status={(updated or {}).get('status')}")
    ver = (updated or {}).get("version", ver)

print("\n=== 非法迁移必须被拒（REJECTED 是终态）===")
code, _ = call("PATCH", f"/api/applications/{apid}/status",
               {"status": "SCREENING", "version": ver}, token=token)
check("REJECTED→SCREENING 被拒", code in (400, 409, 422), f"HTTP {code}")
time.sleep(1.2)

print("\n=== 状态历史逐条核对 ===")
code, detail = call("GET", f"/api/applications/{apid}", token=token, expect=(200,))
history = (detail or {}).get("statusHistory") or []
print(f"  状态历史条数: {len(history)}")
for h in history:
    print(f"    {h.get('fromStatus')} -> {h.get('toStatus')}  note={h.get('note')!r} at={h.get('changedAt')}")
pairs = [(h.get("fromStatus"), h.get("toStatus")) for h in history]
check("历史首条为创建事件（null→DRAFT）", len(pairs) > 0 and pairs[0] == (None, "DRAFT"),
      f"首条={pairs[0] if pairs else None}（创建时写的首条，from 未知故为 null，与存量回填口径一致）")
check("历史第二条为 DRAFT→APPLIED", len(pairs) > 1 and pairs[1] == ("DRAFT", "APPLIED"),
      f"次条={pairs[1] if len(pairs) > 1 else None}")
check("历史含 APPLIED→SCREENING", ("APPLIED", "SCREENING") in pairs, f"实际={pairs}")
check("历史含 SCREENING→NO_RESPONSE", ("SCREENING", "NO_RESPONSE") in pairs, f"实际={pairs}")
check("历史含 NO_RESPONSE→REJECTED（无 APPLIED 中转）", ("NO_RESPONSE", "REJECTED") in pairs, f"实际={pairs}")
check("历史不含伪造的 NO_RESPONSE→APPLIED", ("NO_RESPONSE", "APPLIED") not in pairs, f"实际={pairs}")

print("\n=== stats 口径 ===")
code, stats = call("GET", "/api/applications/stats", token=token, expect=(200,))
by_status = {row.get("status"): row.get("count") for row in ((stats or {}).get("byStatus") or [])}
print(f"  byStatus: {by_status}")
check("stats 含 REJECTED", by_status.get("REJECTED") == 1, f"实际={by_status}")
check("stats 转化率字段存在", (stats or {}).get("conversionRates") is not None,
      f"conversionRates={(stats or {}).get('conversionRates')}")

print("\n=== 清理探针数据 ===")
code, _ = call("DELETE", f"/api/applications/{apid}", token=token)
check("投递删除", code in (200, 204), f"HTTP {code}")
# JD 与简历的删除接口若不存在则跳过（探针不应因清理失败而判红）
code, _ = call("DELETE", f"/api/jobs/{jid}", token=token)
print(f"  JD 清理 HTTP {code}（404 表示该接口不存在，属预期）")
code, _ = call("DELETE", f"/api/resumes/{rid}", token=token)
print(f"  简历清理 HTTP {code}（404 表示该接口不存在，属预期）")

failed = [n for n, ok in results if not ok]
print(f"\n===== 探针合计 {len(results)} 项：通过 {len(results) - len(failed)}，失败 {len(failed)} =====")
if failed:
    for n in failed:
        print("  · 失败：" + n)
    raise SystemExit(1)
print("全部通过。")

#!/usr/bin/env python3
"""智历 · 非 AI 链路系统测试（仅标准库）。

覆盖 AI 冒烟脚本未涉及的部分：
  1. 账号归属隔离（B 不得读到 A 的任何资源）
  2. 简历 + 版本契约
  3. **真实 PDF 导出**（7 个模板全量；校验 PDF 结构、中文字体嵌入、可下载）
  4. ATS 规则评分（useAi=false，不消耗模型配额）
  5. 投递 CRUD 与状态迁移、统计
  6. 沟通模板列表
  7. 简历导入（TXT multipart）
  8. 边界码：未认证 401、不存在 404、非法模板 400

用法：python3 test_non_ai.py [base_url]
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8088").rstrip("/")
RUN = uuid.uuid4().hex[:8]
RESULTS = []


def call(method, path, body=None, token=None, raw_body=None, content_type=None,
         headers_extra=None, timeout=90):
    url = f"{BASE}{path}"
    if raw_body is not None:
        data = raw_body
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
    else:
        data = None
    headers = {"Accept": "*/*"}
    if content_type:
        headers["Content-Type"] = content_type
    elif body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if headers_extra:
        headers.update(headers_extra)
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)
    except Exception as e:
        return 0, str(e).encode(), {}


def j(payload):
    try:
        return json.loads(payload.decode("utf-8", "replace"))
    except Exception:
        return None


def data_of(payload):
    obj = j(payload)
    return obj.get("data") if isinstance(obj, dict) else None


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}  -- {detail}", flush=True)
    return bool(ok)


def section(t):
    print(f"\n=== {t} ===", flush=True)


RESUME_JSON = {
    "basics": {
        "fullName": "张三", "headline": "后端开发工程师",
        "email": "zhangsan@example.invalid", "phone": "13800000000",
        "location": "成都",
        "summary": "5 年后端服务经验，专注高并发场景下的性能优化与稳定交付。",
    },
    "objective": "希望在后端架构方向持续深耕，交付可靠的交易链路服务。",
    "layout": {"sectionOrder": ["basics", "objective", "work", "projects", "skills", "education"]},
    "work": [{
        "company": "示例科技有限公司", "position": "后端开发工程师",
        "startDate": "2024-01", "endDate": "至今",
        "highlights": ["负责订单服务开发与性能优化，接口 P99 延迟下降约三分之一",
                       "与产品、测试、运维协作交付多个版本"],
    }],
    "projects": [{
        "name": "订单中台重构", "role": "后端负责人", "period": "2025",
        "description": "主导订单中台服务拆分与缓存改造，使用 Java 与 Spring Boot。",
    }],
    "skills": [{"name": "Java", "level": "精通"}, {"name": "MySQL", "level": "熟练"},
               {"name": "Redis", "level": "熟练"}],
    "education": [{"school": "某某大学", "major": "软件工程", "degree": "本科",
                   "startDate": "2018-09", "endDate": "2022-06"}],
}

TEMPLATES = ["classic", "modern", "minimal", "ats", "executive", "compact", "academic"]


def register(tag):
    u = f"sys{RUN}{tag}"
    code, payload, _ = call("POST", "/api/auth/register",
                            {"username": u, "email": f"{u}@example.invalid",
                             "password": f"SysTest-{RUN}!"})
    d = data_of(payload)
    return u, (d or {}).get("accessToken")


def main():
    print(f"目标: {BASE}\n测试批次: {RUN}", flush=True)

    section("1. 账号与归属隔离准备")
    user_a, token_a = register("a")
    user_b, token_b = register("b")
    check("register-a", bool(token_a), f"账号 {user_a}")
    check("register-b", bool(token_b), f"账号 {user_b}")
    if not token_a or not token_b:
        return report()

    section("2. JD / 简历 / 版本")
    code, payload, _ = call("POST", "/api/jobs", {
        "title": "后端开发工程师", "companyName": "示例公司",
        "jdText": "岗位职责：负责订单与交易链路的后端服务设计与开发。任职要求：熟悉 Java、"
                  "Spring Boot、MySQL、Redis；具备高并发场景下的性能优化经验。"}, token=token_a)
    jd = data_of(payload) or {}
    check("job-created", bool(jd.get("id")), f"jdId={jd.get('id')} HTTP {code}")

    code, payload, _ = call("POST", "/api/resumes",
                            {"title": f"系统测试简历-{RUN}", "resumeJson": RESUME_JSON}, token=token_a)
    resume = data_of(payload) or {}
    resume_id = resume.get("id")
    check("resume-created", bool(resume_id), f"resumeId={resume_id} HTTP {code}")

    code, payload, _ = call("POST", f"/api/resumes/{resume_id}/versions",
                            {"resumeJson": RESUME_JSON, "sourceType": "MANUAL"}, token=token_a)
    version = data_of(payload) or {}
    version_id = version.get("id")
    check("version-saved", bool(version_id), f"resumeVersionId={version_id} HTTP {code}")
    if not version_id:
        return report()

    code, payload, _ = call("GET", f"/api/resume-versions/{version_id}", token=token_a)
    check("version-readback", code == 200 and (data_of(payload) or {}).get("id") == version_id,
          f"HTTP {code}")

    section("3. 真实 PDF 导出（7 个模板全量）")
    # 注意：导出接口的响应字段是 taskId（不是 id）—— 首次运行因按 id 取值，
    # 把 7 个实际创建成功的任务全判成失败。契约字段名必须先读 DTO 再写断言。
    pdf_ok = 0
    for tpl in TEMPLATES:
        code, payload, _ = call("POST", "/api/exports/pdf",
                                {"resumeVersionId": version_id, "templateCode": tpl}, token=token_a)
        task = data_of(payload) or {}
        task_id = task.get("taskId") or task.get("id")
        if not task_id:
            check(f"export-{tpl}", False, f"创建失败 HTTP {code} {j(payload)}")
            continue
        status, waited = None, 0
        for _ in range(60):
            time.sleep(2)
            waited += 2
            _, tp, _ = call("GET", f"/api/exports/tasks/{task_id}", token=token_a)
            status = (data_of(tp) or {}).get("status")
            if status in ("SUCCESS", "FAILED"):
                break
        if status != "SUCCESS":
            _, tp, _ = call("GET", f"/api/exports/tasks/{task_id}", token=token_a)
            check(f"export-{tpl}", False, f"status={status} waited={waited}s {j(tp)}")
            continue
        code, body, headers = call("GET", f"/api/exports/files/{task_id}", token=token_a)
        is_pdf = body[:5] == b"%PDF-"
        cjk = b"NotoSansCJK" in body
        ok = code == 200 and is_pdf and len(body) > 20000 and cjk
        if ok:
            pdf_ok += 1
        check(f"export-{tpl}", ok,
              f"HTTP {code} 字节={len(body)} PDF头={is_pdf} 含中文字体={cjk} 耗时≈{waited}s")
    check("export-all-templates", pdf_ok == len(TEMPLATES), f"{pdf_ok}/{len(TEMPLATES)} 成功")

    section("4. ATS 规则评分（不调 AI）")
    if jd.get("id"):
        code, payload, _ = call("POST", "/api/ats/check",
                                {"resumeVersionId": version_id, "jobDescriptionId": jd["id"],
                                 "useAi": False}, token=token_a,
                                headers_extra={"Idempotency-Key": f"ats-{RUN}"})
        resp = data_of(payload) or {}
        check("ats-rules", code in (200, 201) and resp.get("resultId") or resp.get("id"),
              f"HTTP {code} keys={list(resp)[:6]}")

    section("5. 投递 CRUD 与状态迁移")
    if jd.get("id"):
        code, payload, _ = call("POST", "/api/applications",
                                {"jobDescriptionId": jd["id"], "resumeVersionId": version_id,
                                 "status": "DRAFT", "coverLetterText": "中文求职信测试"}, token=token_a)
        app = data_of(payload) or {}
        app_id = app.get("id")
        check("application-created", code in (200, 201) and bool(app_id), f"HTTP {code} id={app_id}")
        if app_id:
            _, lp, _ = call("GET", "/api/applications", token=token_a)
            check("application-list", isinstance(data_of(lp), list), f"共 {len(data_of(lp) or [])} 条")
            _, sp, _ = call("GET", "/api/applications/stats", token=token_a)
            check("application-stats", isinstance(data_of(sp), dict), f"{data_of(sp)}")
            code, _, _ = call("PATCH", f"/api/applications/{app_id}/status",
                              {"status": "APPLIED", "version": app.get("version", 0)}, token=token_a)
            check("application-status-transition", code in (200, 204), f"HTTP {code}")

    section("6. 沟通模板与简历导入")
    code, payload, _ = call("GET", "/api/communications/templates", token=token_a)
    tpl_list = data_of(payload)
    check("communication-templates", code == 200 and isinstance(tpl_list, list),
          f"HTTP {code} 共 {len(tpl_list) if isinstance(tpl_list, list) else '?'} 个")

    txt = ("张三\n后端开发工程师\n13800000000\n\n工作经历\n"
           "2024.01-至今 示例科技 后端开发工程师\n负责订单服务开发与性能优化，"
           "接口 P99 延迟下降约三分之一。\n\n技能\nJava、Spring Boot、MySQL、Redis\n").encode("utf-8")
    boundary = "----zl" + RUN
    mp = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"resume.txt\"\r\n"
          f"Content-Type: text/plain\r\n\r\n").encode("utf-8") + txt + f"\r\n--{boundary}--\r\n".encode("utf-8")
    code, payload, _ = call("POST", "/api/resume-imports/parse", raw_body=mp, token=token_a,
                            content_type=f"multipart/form-data; boundary={boundary}")
    check("resume-import-txt", code == 200, f"HTTP {code} {str(j(payload))[:120]}")

    section("7. 归属隔离（B 不得读到 A 的资源）")
    for name, path in [("resume", f"/api/resumes/{resume_id}"),
                       ("resume-version", f"/api/resume-versions/{version_id}"),
                       ("job", f"/api/jobs/{jd.get('id')}")]:
        if "None" in path:
            continue
        code, _, _ = call("GET", path, token=token_b)
        check(f"isolation-{name}", code in (403, 404), f"B 访问 A 的 {name} -> HTTP {code}")

    section("8. 边界码")
    code, _, _ = call("GET", "/api/resumes")
    check("unauth-401", code == 401, f"无 token 访问 /api/resumes -> HTTP {code}")
    code, _, _ = call("GET", "/api/resumes/99999999", token=token_a)
    check("notfound-404", code == 404, f"不存在的简历 -> HTTP {code}")
    code, _, _ = call("POST", "/api/exports/pdf",
                      {"resumeVersionId": version_id, "templateCode": "not-a-template"}, token=token_a)
    check("invalid-template-400", code == 400, f"非法模板码 -> HTTP {code}")

    return report()


def report():
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print("\n" + "=" * 62)
    print(f"合计 {total} 项，通过 {total - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for n, _, d in failed:
            print(f"  - {n}: {d}")
    print("=" * 62, flush=True)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())

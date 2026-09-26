#!/usr/bin/env python3
"""第二批功能验证：补齐 QA / 后端 / 前端三位专家复核时指出的未验证端点。

覆盖专家点名但此前未验证的项：
  A. refresh 主链路（Cookie 携带 → 旋转 → 旧 refresh token 复用应被拒）
  B. DELETE /resumes/{id}、DELETE /jobs/{id} 及其后的引用一致性
  C. POST /api/ai/tasks/{id}/reject（草稿拒绝路径）
  D. ATS ai-retry 对 useAi=false 检查的真实语义
  E. POST /api/ai/tasks 的门禁（无授权 / 未同意）
  F. communications/ai-generate（异步 AI 沟通）
  G. 面试 continue-with-rules / ai/retry / 历史列表
  H. **争议点**：面试 AI 失败时，失败原因是否真的对用户可见（状态响应 aiFailure 是否非空）
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from http.cookies import SimpleCookie

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8088").rstrip("/")
RUN = uuid.uuid4().hex[:8]
RESULTS = []
OBS = []


def call(method, path, body=None, tok=None, extra=None, timeout=180, cookie=None):
    headers = {"Accept": "*/*"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if tok:
        headers["Authorization"] = "Bearer " + tok
    if cookie:
        headers["Cookie"] = cookie
    if extra:
        headers.update(extra)
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)
    except Exception as e:
        return 0, str(e).encode(), {}


def d(raw):
    try:
        o = json.loads(raw.decode())
    except Exception:
        return None
    return o.get("data") if isinstance(o, dict) else None


def sec(t):
    print(f"\n=== {t} ===", flush=True)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}  -- {detail}", flush=True)


def observe(name, code, raw):
    body = raw.decode("utf-8", "replace")[:200]
    OBS.append((name, code, body))
    print(f"  [观测] {name}  -- HTTP {code} | {body}", flush=True)


def refresh_cookie(headers):
    """从响应头里取出刷新令牌 Cookie（name=value 形式）。"""
    sc = headers.get("Set-Cookie")
    if not sc:
        return None
    try:
        c = SimpleCookie()
        c.load(sc)
        return "; ".join(f"{k}={v.value}" for k, v in c.items())
    except Exception:
        return None


RESUME = {"basics": {"fullName": "张三", "headline": "后端工程师"},
          "work": [{"company": "示例科技", "position": "后端工程师",
                    "highlights": ["负责订单服务开发与性能优化"]}]}
JD = {"title": "后端工程师", "companyName": "示例",
      "jdText": "要求熟悉 Java、Spring Boot、MySQL、Redis 与高并发性能优化。"}


def main():
    print(f"目标: {BASE}\n批次: {RUN}", flush=True)
    u = f"b2{RUN}"
    pwd = f"B2-{RUN}!"

    # ---------- A. refresh 主链路 ----------
    sec("A. refresh 主链路（Cookie → 旋转 → 旧令牌复用）")
    code, raw, h = call("POST", "/api/auth/register",
                        {"username": u, "email": f"{u}@example.invalid", "password": pwd})
    tok = (d(raw) or {}).get("accessToken")
    ck = refresh_cookie(h)
    check("A1 register 并取得刷新 Cookie", bool(tok) and bool(ck),
          f"HTTP {code} Cookie={'有' if ck else '无'}")

    code, raw, h2 = call("POST", "/api/auth/login", {"username": u, "password": pwd})
    ck2 = refresh_cookie(h2) or ck
    tok = (d(raw) or {}).get("accessToken")
    check("A2 login 并取得刷新 Cookie", bool(tok) and bool(ck2), f"HTTP {code}")

    code, raw, h3 = call("POST", "/api/auth/refresh", {}, cookie=ck2)
    new_tok = (d(raw) or {}).get("accessToken")
    ck3 = refresh_cookie(h3)
    check("A3 refresh 成功换发新的 access token", code == 200 and bool(new_tok),
          f"HTTP {code} 新 token={'有' if new_tok else '无'}")
    code, raw, _ = call("GET", "/api/auth/me", tok=new_tok)
    check("A4 新 access token 可用", code == 200, f"HTTP {code}")

    if ck3:
        code, raw, _ = call("POST", "/api/auth/refresh", {}, cookie=ck2)
        observe("A5 旧 refresh token 复用（旋转后应被拒；若被拒则说明撤销族生效）", code, raw)
        code2, raw2, _ = call("POST", "/api/auth/refresh", {}, cookie=ck3)
        observe("A6 新 refresh token 是否仍可用（用于区分『复用即整族撤销』与『仅旧令牌失效』）", code2, raw2)

    # ---------- B. 删除简历 / JD 与引用一致性 ----------
    sec("B. DELETE /resumes/{id} 与 DELETE /jobs/{id}")
    code, raw, _ = call("POST", "/api/jobs", JD, tok=tok)
    jid = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", "/api/resumes", {"title": f"待删-{RUN}", "resumeJson": RESUME}, tok=tok)
    rid = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", f"/api/resumes/{rid}/versions",
                        {"resumeJson": RESUME, "sourceType": "MANUAL"}, tok=tok)
    rvid = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", f"/api/jobs/{jid}/parse", {}, tok=tok)
    check("B0 前置数据就绪", bool(jid and rid and rvid), f"jid={jid} rid={rid} rvid={rvid}")

    if rid:
        code, raw, _ = call("DELETE", f"/api/resumes/{rid}", tok=tok)
        c2, _, _ = call("GET", f"/api/resumes/{rid}", tok=tok)
        c3, _, _ = call("GET", f"/api/resume-versions/{rvid}", tok=tok)
        check("B1 删除简历后自身与版本均不可读（404）",
              code in (200, 204) and c2 == 404 and c3 == 404,
              f"DELETE {code} → 简历 {c2} / 版本 {c3}")
    if jid:
        code, raw, _ = call("DELETE", f"/api/jobs/{jid}", tok=tok)
        c2, _, _ = call("GET", f"/api/jobs/{jid}", tok=tok)
        check("B2 删除 JD 后不可读（404）", code in (200, 204) and c2 == 404,
              f"DELETE {code} → GET {c2}")

    # ---------- C. 生成任务 reject ----------
    sec("C. POST /api/ai/tasks/{id}/reject（草稿拒绝路径）")
    u2 = f"b2c{RUN}"
    code, raw, h = call("POST", "/api/auth/register",
                        {"username": u2, "email": f"{u2}@example.invalid", "password": pwd})
    tok2, ck = (d(raw) or {}).get("accessToken"), refresh_cookie(h)
    code, raw, _ = call("POST", "/api/ai/consent",
                        {"policyVersion": "v1.2.0", "providerCode": "bailian",
                         # 后续 F 组要验证 communications/ai-generate（需 COMMUNICATION_GENERATE 范围）
                         "taskScopes": ["JOB_MATERIAL_SELECTION", "JOB_GENERATION",
                                        "COMMUNICATION_GENERATE"],
                         # ⚠ COMMUNICATION_GENERATE 要求类别 ["RESUME","JOB_DESCRIPTION"] ——
                         #   漏 RESUME 会让 F1 以 403 失败（实测踩过）
                         "dataCategories": ["CAREER_MATERIAL", "JOB_DESCRIPTION",
                                            "PERSONAL_PROFILE", "RESUME"],
                         "noticeHash": f"b2-{RUN}"}, tok=tok2)
    code, raw, _ = call("POST", "/api/jobs", JD, tok=tok2)
    jid2 = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", "/api/career-materials",
                        {"materialType": "WORK_EXPERIENCE", "title": "前端验证岗",
                         "contentJson": {"organization": "示例科技", "role": "后端工程师",
                                         "summary": "负责订单服务开发"},
                         "sourceText": "负责订单服务开发与性能优化，熟悉 Java、Spring Boot、MySQL、Redis。"},
                        tok=tok2)
    mid = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", "/api/ai/select-materials-for-job",
                        {"jobDescriptionId": jid2, "includedMaterialIds": [mid],
                         "preferredMaterialIds": [], "excludedMaterialIds": [],
                         "resumeTitle": f"拒绝验证-{RUN}"}, tok=tok2,
                        extra={"Idempotency-Key": f"b2-sel-{RUN}"})
    sel = d(raw) or {}
    stid = sel.get("taskId") or sel.get("id")
    check("C1 选材任务已创建", bool(stid), f"HTTP {code} taskId={stid}")
    if stid:
        for _ in range(40):
            time.sleep(3)
            _, tr, _ = call("GET", f"/api/ai/tasks/{stid}", tok=tok2)
            if (d(tr) or {}).get("status") in ("SUCCESS", "FAILED"):
                break
        check("C2 选材任务成功", (d(tr) or {}).get("status") == "SUCCESS",
              f"status={(d(tr) or {}).get('status')}")
        # 确认选材 → 生成 → 拒绝
        code, raw, _ = call("POST", f"/api/ai/tasks/{stid}/confirm-materials",
                            {"confirmedMaterialIds": [mid]}, tok=tok2)
        observe("C3 确认选材", code, raw)
        gt = d(raw) or {}
        gid = gt.get("taskId") or gt.get("id")
        if gid:
            for _ in range(60):
                time.sleep(3)
                _, tr2, _ = call("GET", f"/api/ai/tasks/{gid}", tok=tok2)
                if (d(tr2) or {}).get("status") in ("SUCCESS", "FAILED"):
                    break
            check("C4 生成任务成功", (d(tr2) or {}).get("status") == "SUCCESS",
                  f"status={(d(tr2) or {}).get('status')}")
            code, raw, _ = call("POST", f"/api/ai/tasks/{gid}/reject",
                                {"reason": "内容不符合预期"}, tok=tok2)
            check("C5 reject 拒绝草稿", code in (200, 204), f"HTTP {code}")
            code, raw, _ = call("GET", f"/api/ai/tasks/{gid}", tok=tok2)
            observe("C6 拒绝后的任务状态", code, raw)

    # ---------- D. ATS ai-retry 语义 ----------
    sec("D. POST /api/ats/checks/{id}/ai-retry 的真实语义")
    code, raw, _ = call("POST", "/api/resumes", {"title": f"ATS重试-{RUN}", "resumeJson": RESUME}, tok=tok2)
    rid2 = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", f"/api/resumes/{rid2}/versions",
                        {"resumeJson": RESUME, "sourceType": "MANUAL"}, tok=tok2)
    rvid2 = (d(raw) or {}).get("id")
    if rvid2 and jid2:
        code, raw, _ = call("POST", "/api/ats/check",
                            {"resumeVersionId": rvid2, "jobDescriptionId": jid2, "useAi": False},
                            tok=tok2, extra={"Idempotency-Key": f"b2-ats-{RUN}"})
        aid = (d(raw) or {}).get("id")
        check("D1 纯规则检查已创建", bool(aid), f"HTTP {code} id={aid}")
        if aid:
            code, raw, _ = call("POST", f"/api/ats/checks/{aid}/ai-retry", {}, tok=tok2)
            body = d(raw) or {}
            observe(f"D2 对 useAi=false 的检查发起 ai-retry（analysisStatus={body.get('analysisStatus')} "
                    f"analysisSource={body.get('analysisSource')} aiTaskId={body.get('aiTaskId')}）", code, raw)
            time.sleep(20)
            code, raw, _ = call("GET", f"/api/ats/checks/{aid}", tok=tok2)
            b2 = d(raw) or {}
            observe(f"D3 20s 后该检查状态（analysisSource={b2.get('analysisSource')} "
                    f"analysisStatus={b2.get('analysisStatus')}）", code, raw)

    # ---------- E. POST /api/ai/tasks 门禁 ----------
    sec("E. POST /api/ai/tasks 门禁（未同意 AI 的账号）")
    u3 = f"b2e{RUN}"
    code, raw, h = call("POST", "/api/auth/register",
                        {"username": u3, "email": f"{u3}@example.invalid", "password": pwd})
    tok3 = (d(raw) or {}).get("accessToken")
    code, raw, _ = call("POST", "/api/ai/tasks",
                        {"taskType": "JOB_GENERATION", "inputSnapshot": {}}, tok=tok3)
    check("E1 未授权时创建 AI 任务被拒（4xx）", 400 <= code < 500,
          f"HTTP {code} {raw.decode('utf-8', 'replace')[:130]}")

    # ---------- F. communications/ai-generate ----------
    sec("F. POST /api/communications/ai-generate（异步 AI 沟通）")
    code, raw, _ = call("POST", "/api/resumes", {"title": f"沟通-{RUN}", "resumeJson": RESUME}, tok=tok2)
    rid3 = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", f"/api/resumes/{rid3}/versions",
                        {"resumeJson": RESUME, "sourceType": "MANUAL"}, tok=tok2)
    rvid3 = (d(raw) or {}).get("id")
    if rvid3 and jid2:
        code, raw, _ = call("POST", "/api/communications/ai-generate",
                            {"resumeVersionId": rvid3, "jobDescriptionId": jid2,
                             "type": "EMAIL", "outputLanguage": "ZH_CN"}, tok=tok2,
                            extra={"Idempotency-Key": f"b2-ai-gen-{RUN}"})
        ai = d(raw) or {}
        check("F1 ai-generate 已受理（202；注意该接口要求必填 Idempotency-Key 头）",
              code in (200, 201, 202),
              f"HTTP {code} {json.dumps(ai, ensure_ascii=False)[:150]}")

    # ---------- G. 面试其余端点 ----------
    sec("G. 面试 continue-with-rules / ai/retry / 历史列表")
    code, raw, _ = call("GET", "/api/interviews", tok=tok2)
    check("G1 面试历史列表", code == 200 and isinstance(d(raw), list), f"HTTP {code} 共 {len(d(raw) or [])} 条")
    code, raw, _ = call("POST", f"/api/interviews/99999999/ai/retry", {}, tok=tok2)
    observe("G2 对不存在的会话 ai/retry（应 404）", code, raw)
    code, raw, _ = call("POST", f"/api/interviews/99999999/continue-with-rules", {}, tok=tok2)
    observe("G3 对不存在的会话 continue-with-rules（应 404）", code, raw)

    # ---------- H. 争议点：面试 AI 失败是否对用户可见 ----------
    sec("H. 争议点核实：面试 AI 失败时 aiFailure 是否真的暴露（只授 RESUME，故意不授 INTERVIEW_ANSWER）")
    u4 = f"b2h{RUN}"
    code, raw, _ = call("POST", "/api/auth/register",
                        {"username": u4, "email": f"{u4}@example.invalid", "password": pwd})
    tok4 = (d(raw) or {}).get("accessToken")
    code, raw, _ = call("POST", "/api/ai/consent",
                        {"policyVersion": "v1.2.0", "providerCode": "bailian",
                         "taskScopes": ["INTERVIEW_COACH"], "dataCategories": ["RESUME"],
                         "noticeHash": f"b2h-{RUN}"}, tok=tok4)
    check("H1 故意只授 RESUME（缺 INTERVIEW_ANSWER）", code in (200, 201), f"HTTP {code}")
    code, raw, _ = call("POST", "/api/resumes", {"title": f"缺授权-{RUN}", "resumeJson": RESUME}, tok=tok4)
    ridh = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", f"/api/resumes/{ridh}/versions",
                        {"resumeJson": RESUME, "sourceType": "MANUAL"}, tok=tok4)
    rvidh = (d(raw) or {}).get("id")
    code, raw, _ = call("POST", "/api/interviews/start",
                        {"sourceType": "PLATFORM_RESUME", "resumeVersionId": rvidh,
                         "interviewMode": "COMPREHENSIVE", "targetQuestionCount": 4,
                         "outputLanguage": "ZH_CN"}, tok=tok4,
                        extra={"Idempotency-Key": f"b2h-start-{RUN}"})
    iv = d(raw) or {}
    print(f"    start → HTTP {code} status={iv.get('status')} aiFailure={json.dumps(iv.get('aiFailure'), ensure_ascii=False)}")
    sidh = iv.get("interviewId")
    time.sleep(8)
    if sidh:
        code, raw, _ = call("GET", f"/api/interviews/{sidh}", tok=tok4)
        s = d(raw) or {}
        af = s.get("aiFailure")
        observe("H2 状态响应里的 aiFailure", code,
                json.dumps({"status": s.get("status"), "aiFailure": af}, ensure_ascii=False).encode())
        check("H3 AI 失败原因对用户可见（aiFailure 非空）", af not in (None, {}, ""),
              f"aiFailure={json.dumps(af, ensure_ascii=False)[:150]}")
        code, raw, _ = call("POST", f"/api/interviews/{sidh}/ai/retry", {}, tok=tok4)
        # 本账号**故意**只授 RESUME（缺 INTERVIEW_ANSWER），因此重试会被同意门禁以 403 拦下 ——
        # 这仍证明"恢复入口存在且可达"；200/201/409 属入口正常工作的其他形态。
        check("H4 存在 ai/retry 恢复入口（403=入口可达但授权不足，亦属预期）",
              code in (200, 201, 409, 403), f"HTTP {code}")

    # ---------- I. 无密钥降级路径（对应 QA 复核 R6"非首分支可达"：降级路径必须能强制走到并断言） ----------
    sec("I. 无密钥降级：AI 不可用时失败必须显式暴露（aiFailure 非空、不静默滞留）")
    # 自适应：有可用模型的环境（如测试环境）首题生成会成功 → 本节不适用、跳过；
    # 无密钥环境（CI）→ INITIAL_QUESTION 确定性失败，断言失败原因经 aiFailure 暴露。
    if rvid3:
        code, raw, _ = call("POST", "/api/ai/consent",
                            {"policyVersion": "v1.2.0", "providerCode": "bailian",
                             "taskScopes": ["INTERVIEW_COACH"],
                             "dataCategories": ["RESUME", "INTERVIEW_ANSWER", "JOB_DESCRIPTION"],
                             "noticeHash": f"b2i-{RUN}"}, tok=tok2)
        check("I0 授予面试同意", code in (200, 201), f"HTTP {code}")
        code, raw, _ = call("POST", "/api/interviews/start",
                            {"sourceType": "PLATFORM_RESUME", "resumeVersionId": rvid3,
                             "interviewMode": "COMPREHENSIVE", "targetQuestionCount": 4,
                             "outputLanguage": "ZH_CN"}, tok=tok2,
                            extra={"Idempotency-Key": f"b2i-start-{RUN}"})
        iv = d(raw) or {}
        sidh = iv.get("interviewId")
        status0 = iv.get("status")
        if status0 == "AWAITING_ANSWER":
            observe("I1 目标环境配置了可用模型 → 降级路径不适用（跳过；AI 可用性由 AI 套件验证）",
                    code, raw)
        else:
            af0 = iv.get("aiFailure")
            state = {"status": status0, "aiFailure": af0}
            if sidh:
                time.sleep(5)
                code, raw, _ = call("GET", f"/api/interviews/{sidh}", tok=tok2)
                s = d(raw) or {}
                state = {"status": s.get("status"), "aiFailure": s.get("aiFailure") or af0}
            observe("I1 无模型时的会话状态与 aiFailure", code,
                    json.dumps(state, ensure_ascii=False).encode())
            check("I2 AI 不可用必须显式暴露（aiFailure 非空）",
                  (state.get("aiFailure") or {}) not in (None, {}, ""),
                  f"aiFailure={json.dumps(state.get('aiFailure'), ensure_ascii=False)[:160]}")
            check("I3 会话不得静默滞留在评估中",
                  state.get("status") in ("AI_ACTION_REQUIRED", "COMPLETED"),
                  f"status={state.get('status')}（EVALUATING_ANSWER/GENERATING_QUESTION 属静默滞留）")
    else:
        print("  [SKIP] I 降级路径 —— 前置简历版本未创建（F 组失败），本轮未验证。", flush=True)

    return report()


def report():
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print("\n" + "=" * 66)
    print(f"断言合计 {total} 项，通过 {total - len(failed)}，失败 {len(failed)}")
    for n, _ in failed:
        print(f"  - {n}")
    if OBS:
        print(f"\n需人工判断的观测（{len(OBS)} 条）：")
        for n, c, b in OBS:
            print(f"  · {n} → HTTP {c} | {b[:170]}")
    print("=" * 66, flush=True)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""智历 · 远程 AI 全链路冒烟测试（仅标准库，无第三方依赖）。

针对已部署的服务走真实 HTTP + 真实百炼模型链，覆盖全部 AI 任务类型：
  JOB_MATERIAL_SELECTION / JOB_GENERATION / ATS_ANALYSIS / COMMUNICATION_GENERATE
  INLINE_OPTIMIZE / ACHIEVEMENT_GUIDANCE / INTERVIEW_COACH

用法：
    python test_remote_ai.py [base_url]
    python test_remote_ai.py http://8.160.165.227

设计取舍：
- **不清理测试账号**：测试结束时打印账号口令，便于在浏览器里登录查看真实生成结果。
  账号为合成数据，可在 UI 的账户页删除。
- 断言只针对「契约与真实产出」，不针对具体文案（模型输出本就非确定）。
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://8.160.165.227").rstrip("/")
RUN = uuid.uuid4().hex[:8]
USERNAME = f"aitest{RUN}"
EMAIL = f"aitest-{RUN}@example.invalid"
PASSWORD = f"AiTest-{RUN}!"

RESULTS = []
TOKEN = None
STARTED = time.time()


# ---------------------------------------------------------------- HTTP 基础

def call(method, path, body=None, token=None, extra_headers=None, timeout=120):
    """发起一次 API 调用，返回 (status_code, parsed_json_or_text)。"""
    url = f"{BASE}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, _maybe_json(raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        return error.code, _maybe_json(raw)
    except Exception as error:  # 网络层失败
        return 0, {"_transportError": str(error)}


def _maybe_json(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw[:400]}


def data_of(payload):
    """取统一响应体里的 data 字段。"""
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    return None


def is_2xx(code):
    """创建类接口按 REST 语义返回 200/201，异步 AI 任务返回 202，统一按 2xx 判定。
    真正的契约断言放在响应体上（是否返回 id / 候选内容），而不是状态码的具体取值。"""
    return isinstance(code, int) and 200 <= code < 300


def check(name, condition, detail="", blocked=False):
    status = "PASS" if condition else ("BLOCKED" if blocked else "FAIL")
    RESULTS.append({"name": name, "status": status, "detail": detail})
    icon = {"PASS": "PASS", "FAIL": "FAIL", "BLOCKED": "BLOCK"}[status]
    print(f"  [{icon}] {name}" + (f"  -- {detail}" if detail else ""), flush=True)
    return condition


def poll_ai_task(task_id, token, deadline_seconds=600):
    """轮询 AI 任务到终态。

    默认窗口 600s 是实测结论：AI worker 按 id **串行**执行任务（claimBatch 每次取 1 条），
    而一次 JOB_GENERATION 实测耗时 477s（链首超时 300s + 顺延后成功约 177s），
    排在其后的任务会因此排队。窗口过短会把「仍在正常排队执行」误判为失败。
    """
    deadline = time.time() + deadline_seconds
    last = None
    while time.time() < deadline:
        code, payload = call("GET", f"/api/ai/tasks/{task_id}", token=token)
        task = data_of(payload)
        if not isinstance(task, dict):
            return {"status": "POLL_ERROR", "_http": code, "_payload": payload}
        last = task
        if task.get("status") in ("SUCCESS", "FAILED", "CANCELLED"):
            return task
        time.sleep(2)
    return last or {"status": "POLL_TIMEOUT"}


def section(title):
    print(f"\n=== {title} ===", flush=True)


def summarize_failure(task):
    """从失败任务里提取可读原因（不打印可能的敏感正文）。"""
    if not isinstance(task, dict):
        return "任务响应异常"
    return f"status={task.get('status')} retryCount={task.get('retryCount')} " \
           f"error={str(task.get('errorMessage'))[:160]}"


# ---------------------------------------------------------------- 测试主体

def main():
    print(f"目标: {BASE}")
    print(f"测试账号: {USERNAME} / {EMAIL} / {PASSWORD}")

    section("0. 环境前置")
    code, payload = call("GET", "/api/system/health")
    health = data_of(payload) or {}
    chain = {c.get("capability"): c.get("status") for c in health.get("checks", [])}
    check("api-health", is_2xx(code), f"HTTP {code}")
    check("ai-provider", chain.get("ai-provider") == "UP", f"密钥配置: {chain.get('ai-provider')}")
    check("ai-model-chain", chain.get("ai-model-chain") == "UP", f"模型链: {chain.get('ai-model-chain')}")
    if chain.get("ai-model-chain") != "UP":
        print("\n模型链不可用，后续 AI 断言将整体阻塞。")
        return report()

    section("1. 账号与前置数据")
    code, payload = call("POST", "/api/auth/register",
                         {"username": USERNAME, "email": EMAIL, "password": PASSWORD})
    registered = data_of(payload)
    token = registered.get("accessToken") if isinstance(registered, dict) else None
    check("register", is_2xx(code) and bool(token), f"HTTP {code}")
    if not token:
        print("\n注册失败，无法继续。")
        return report()
    globals()["TOKEN"] = token

    code, payload = call("GET", "/api/auth/me", token=token)
    check("auth-me", is_2xx(code) and data_of(payload, ).get("username") == USERNAME, f"HTTP {code}")

    work = data_of(call("POST", "/api/career-materials", {
        "materialType": "WORK_EXPERIENCE", "title": "后端服务交付",
        "contentJson": {"company": "示例科技", "position": "后端工程师", "period": "2024-2026",
                        "description": "负责订单服务的开发与性能优化"},
        "sourceText": "负责订单服务开发，与产品、测试和运维协作交付。",
        "usagePreference": "PREFERRED"}, token=token)[1])
    project = data_of(call("POST", "/api/career-materials", {
        "materialType": "PROJECT_EXPERIENCE", "title": "订单中台重构",
        "contentJson": {"name": "订单中台重构", "role": "后端负责人", "period": "2025",
                        "description": "主导订单中台服务拆分与缓存改造"},
        "sourceText": "主导订单中台重构，使用 Java 与 Spring Boot。",
        "usagePreference": "PREFERRED"}, token=token)[1])
    achievement = data_of(call("POST", "/api/career-materials", {
        "materialType": "ACHIEVEMENT", "title": "接口性能提升",
        "contentJson": {"relatedMaterialId": project.get("id"), "scenario": "高峰期流量",
                        "action": "引入缓存与慢查询治理", "outcome": "接口响应明显改善",
                        "period": "2025 Q2", "metricName": "P99 延迟", "metricDisplayMode": "RANGE",
                        "metricDisplayValue": "下降约三分之一", "metricExactValue": "37.4%"},
        "usagePreference": "PREFERRED"}, token=token)[1])
    skill = data_of(call("POST", "/api/career-materials", {
        "materialType": "SKILL_EVIDENCE", "title": "Java 服务端能力证据",
        "contentJson": {"skillName": "Java", "category": "后端", "proficiency": "熟练",
                        "yearsOfExperience": "5 年", "lastUsedAt": "2026-08",
                        "relatedMaterialIds": [work.get("id"), project.get("id")],
                        "applicationDescription": "长期使用 Spring Boot 构建并维护接口服务",
                        "outcomeEvidence": "支撑了稳定的生产发布"},
        "usagePreference": "NORMAL"}, token=token)[1])
    job = data_of(call("POST", "/api/jobs", {
        "title": "后端开发工程师", "companyName": "示例公司",
        "jdText": "岗位职责：负责订单与交易链路的后端服务设计与开发。任职要求：熟悉 Java、"
                  "Spring Boot、MySQL、Redis；具备高并发场景下的性能优化经验；"
                  "有良好的跨团队协作与沟通能力。"}, token=token)[1])
    call("PUT", "/api/personal-profile", {
        "fullName": "测试候选人", "email": EMAIL, "phone": "13800000000", "location": "成都",
        "website": "https://example.invalid",
        "profileSummary": "具备 Spring Boot 项目经验的后端工程师。",
        "targetRoleTitles": ["后端开发工程师"], "targetSeniority": "中级",
        "targetIndustries": ["互联网"], "targetWorkPreferences": ["成都", "混合办公"],
        "careerPositioningSummary": "专注可靠服务与跨团队交付的后端工程师。"}, token=token)

    material_ids = [m.get("id") for m in (work, project, achievement, skill) if isinstance(m, dict)]
    check("seed-materials", len(material_ids) == 4, f"已创建 {len(material_ids)} 条职业资料")
    check("seed-job", isinstance(job, dict) and bool(job.get("id")), f"JD id={job.get('id') if isinstance(job, dict) else None}")

    section("2. AI 授权（覆盖全部 9 个任务范围）")
    scopes = ["JOB_MATERIAL_SELECTION", "JOB_GENERATION", "RESUME_OPTIMIZE", "ACHIEVEMENT_GUIDANCE",
              "COMMUNICATION_GENERATE", "MATERIAL_IMPORT", "INLINE_OPTIMIZE", "INTERVIEW_COACH", "ATS_ANALYSIS"]
    code, payload = call("POST", "/api/ai/consent", {
        "policyVersion": "v1.2.0", "providerCode": "bailian", "taskScopes": scopes,
        "dataCategories": ["CAREER_MATERIAL", "JOB_DESCRIPTION", "PERSONAL_PROFILE", "RESUME"],
        "noticeHash": "remote-ai-smoke"}, token=token)
    check("ai-consent-grant", is_2xx(code), f"HTTP {code}")

    section("3. JOB_MATERIAL_SELECTION（AI 选材）")
    code, payload = call("POST", "/api/ai/select-materials-for-job", {
        "jobDescriptionId": job["id"], "includedMaterialIds": material_ids,
        "preferredMaterialIds": [], "excludedMaterialIds": [],
        "resumeTitle": f"AI 冒烟测试简历-{RUN}"}, token=token,
        extra_headers={"Idempotency-Key": f"sel-{RUN}"})
    selection = data_of(payload)
    ok = check("selection-task-created", is_2xx(code) and isinstance(selection, dict) and selection.get("id"),
               f"HTTP {code} taskId={selection.get('id') if isinstance(selection, dict) else None}")
    selection_task = None
    if ok:
        selection_task = poll_ai_task(selection["id"], token)
        check("selection-task-success", selection_task.get("status") == "SUCCESS",
              summarize_failure(selection_task))
        if selection_task.get("status") == "SUCCESS":
            result = selection_task.get("resultJson") or {}
            recommended = result.get("recommended") or []
            check("selection-returns-recommendations", len(recommended) > 0,
                  f"推荐 {len(recommended)} 条 / 未选 {len(result.get('unselected') or [])} 条")

    section("4. JOB_GENERATION（AI 生成岗位简历草稿）")
    resume_version_id = None
    if selection_task and selection_task.get("status") == "SUCCESS":
        result = selection_task.get("resultJson") or {}
        recommended_ids = [item.get("materialId") for item in (result.get("recommended") or [])]
        if not recommended_ids:
            recommended_ids = material_ids[:2]
        code, payload = call("POST", f"/api/ai/tasks/{selection_task['id']}/confirm-materials", {
            "taskUpdatedAt": selection_task.get("updatedAt"),
            "selectedMaterialIds": recommended_ids,
            "forcedIncludedMaterialIds": [],
            "resumeTitle": f"AI 冒烟测试简历-{RUN}"}, token=token,
            extra_headers={"Idempotency-Key": f"cfm-{RUN}"})
        generation = data_of(payload)
        ok = check("generation-task-created", is_2xx(code) and isinstance(generation, dict) and generation.get("id"),
                   f"HTTP {code}")
        if ok:
            generation_task = poll_ai_task(generation["id"], token)
            check("generation-task-success", generation_task.get("status") == "SUCCESS",
                  summarize_failure(generation_task))
            if generation_task.get("status") == "SUCCESS":
                draft = (generation_task.get("resultJson") or {}).get("draftResumeJson") or {}
                basics = draft.get("basics") or {}
                check("generation-draft-has-content",
                      bool(basics) or any(k for k in draft if not k.startswith("_")),
                      f"草稿顶层字段: {[k for k in draft if not k.startswith('_')][:8]}")
                items = [{"outputPath": f"{key}[{index}]" if isinstance(value, list) else key,
                          "decision": "ACCEPT"}
                         for key, value in draft.items() if not key.startswith("_")
                         for index in (range(len(value)) if isinstance(value, list) else [0])]
                code, payload = call("POST", f"/api/ai/tasks/{generation_task['id']}/confirm", {
                    "taskUpdatedAt": generation_task.get("updatedAt"), "items": items,
                    "additionalResumeJson": {}}, token=token,
                    extra_headers={"Idempotency-Key": f"cfmres-{RUN}"})
                confirmed = data_of(payload)
                resume_version_id = confirmed.get("resumeVersionId") if isinstance(confirmed, dict) else None
                check("generation-confirm-creates-version", is_2xx(code) and bool(resume_version_id),
                      f"HTTP {code} resumeVersionId={resume_version_id}")

    section("5. ATS_ANALYSIS（AI 深度分析）")
    if not resume_version_id:
        # 生成失败时（例如草稿溯源校验不通过）不放弃后续断言：
        # 直接建一份简历版本，把其余 AI 能力测完。生成失败已在第 4 节单独记录。
        print("  （生成未产出简历版本，改为直接创建一份以继续验证后续 AI 能力）")
        fallback_json = {
            "basics": {"name": "测试候选人", "title": "后端开发工程师",
                       "summary": "具备 Spring Boot 项目经验的后端工程师，负责订单服务开发与性能优化。"},
            "work": [{"company": "示例科技", "position": "后端工程师",
                      "description": "负责订单服务的开发与性能优化。"}],
            "projects": [{"name": "订单中台重构", "role": "后端负责人",
                          "description": "主导订单中台服务拆分与缓存改造。"}],
            "education": [{"school": "示例大学", "major": "软件工程", "degree": "本科"}],
            "skills": [{"name": "Java"}, {"name": "Spring Boot"}, {"name": "MySQL"}, {"name": "Redis"}],
        }
        code, payload = call("POST", "/api/resumes",
                             {"title": f"AI 冒烟直建-{RUN}", "resumeJson": fallback_json}, token=token)
        created = data_of(payload)
        if is_2xx(code) and isinstance(created, dict) and created.get("id"):
            versions = data_of(call("GET", f"/api/resumes/{created['id']}/versions", token=token)[1]) or []
            if versions:
                resume_version_id = versions[0].get("id")
        check("resume-version-available-for-remaining-ai", bool(resume_version_id),
              f"resumeVersionId={resume_version_id}")

    if resume_version_id:
        code, payload = call("POST", "/api/ats/check", {
            "resumeVersionId": resume_version_id, "jobDescriptionId": job["id"], "useAi": True},
            token=token, extra_headers={"Idempotency-Key": f"ats-{RUN}"}, timeout=180)
        ats = data_of(payload)
        ok = check("ats-check-created", is_2xx(code) and isinstance(ats, dict) and ats.get("id"),
                   f"HTTP {code}")
        if ok:
            check("ats-rules-scored", isinstance(ats.get("totalScore"), (int, float)),
                  f"规则分={ats.get('totalScore')}")
            # 与 poll_ai_task 同理：AI worker 串行执行，ATS 的 AI 分析会排在生成任务之后
            deadline = time.time() + 600
            while time.time() < deadline and ats.get("analysisStatus") == "ANALYZING":
                time.sleep(3)
                ats = data_of(call("GET", f"/api/ats/checks/{ats['id']}", token=token)[1]) or ats
            check("ats-ai-analysis-hybrid", ats.get("analysisSource") == "HYBRID",
                  f"analysisStatus={ats.get('analysisStatus')} source={ats.get('analysisSource')} "
                  f"fallback={((ats.get('fallback') or {}).get('code')) if ats.get('fallback') else None}")
            insights = ats.get("aiInsights") or {}
            check("ats-ai-insights-present", bool(insights.get("summary")),
                  f"覆盖项 {len(insights.get('semanticCoverage') or [])} / 优先级 {len(insights.get('prioritizedActions') or [])}")
            globals()["ATS_SNAPSHOT"] = {
                "totalScore": ats.get("totalScore"),
                "analysisSource": ats.get("analysisSource"),
                "summary": (insights.get("summary") or "")[:120],
                "prioritizedActions": len(insights.get("prioritizedActions") or []),
            }
    else:
        check("ats-check-created", False, "缺少 resumeVersionId，跳过", blocked=True)

    section("6. COMMUNICATION_GENERATE（AI 沟通文案）")
    if resume_version_id:
        code, payload = call("POST", "/api/communications/generate", {
            "resumeVersionId": resume_version_id, "jobDescriptionId": job["id"], "type": "EMAIL"},
            token=token, timeout=180)
        draft = data_of(payload)
        body = draft.get("draft") if isinstance(draft, dict) else None
        check("communication-generated", is_2xx(code) and bool(body), f"HTTP {code} 长度={len(body or '')}")
    else:
        check("communication-generated", False, "缺少 resumeVersionId，跳过", blocked=True)

    section("7. INLINE_OPTIMIZE（AI 内联润色）")
    if resume_version_id:
        code, payload = call("POST", "/api/ai/inline-optimize", {
            "resumeVersionId": resume_version_id, "section": "work",
            "content": "负责订单服务的开发与性能优化",
            "jobDescriptionId": job["id"]}, token=token,
            extra_headers={"Idempotency-Key": f"inline-{RUN}"})
        inline = data_of(payload)
        ok = check("inline-task-created", is_2xx(code) and isinstance(inline, dict) and inline.get("id"), f"HTTP {code}")
        if ok:
            inline_task = poll_ai_task(inline["id"], token)
            check("inline-task-success", inline_task.get("status") == "SUCCESS", summarize_failure(inline_task))
            if inline_task.get("status") == "SUCCESS":
                result = inline_task.get("resultJson") or {}
                candidates = result.get("candidates") or []
                check("inline-returns-candidates", len(candidates) > 0,
                      f"候选 {len(candidates)} 条，需人工确认={result.get('requiresManualConfirmation')}")
    else:
        check("inline-task-created", False, "缺少 resumeVersionId，跳过", blocked=True)

    section("8. ACHIEVEMENT_GUIDANCE（AI 量化成果引导）")
    if resume_version_id:
        code, payload = call("POST", "/api/ai/achievement-guidance", {
            "resumeVersionId": resume_version_id, "section": "work",
            "content": "负责订单服务的开发与性能优化"}, token=token,
            extra_headers={"Idempotency-Key": f"achievement-{RUN}"})
        guidance = data_of(payload)
        ok = check("achievement-task-created", is_2xx(code) and isinstance(guidance, dict) and guidance.get("id"),
                   f"HTTP {code}")
        if ok:
            guidance_task = poll_ai_task(guidance["id"], token)
            check("achievement-task-success", guidance_task.get("status") == "SUCCESS",
                  summarize_failure(guidance_task))
    else:
        check("achievement-task-created", False, "缺少 resumeVersionId，跳过", blocked=True)

    section("9. INTERVIEW_COACH（AI 模拟面试首题）")
    if resume_version_id:
        code, payload = call("POST", "/api/interviews/start", {
            "sourceType": "PLATFORM_RESUME", "resumeVersionId": resume_version_id,
            "jobDescriptionId": job["id"], "interviewMode": "JD_TARGETED",
            "targetQuestionCount": 4, "outputLanguage": "ZH_CN"}, token=token,
            extra_headers={"Idempotency-Key": f"interview-{RUN}"}, timeout=180)
        session = data_of(payload)
        ok = check("interview-started", is_2xx(code) and isinstance(session, dict),
                   f"HTTP {code}")
        if ok:
            execution_mode = session.get("executionMode")
            status = session.get("status")
            check("interview-uses-ai-mode", execution_mode == "AI",
                  f"executionMode={execution_mode} status={status}")
            # 首题由 AI 异步生成：同步返回 AI_ACTION_REQUIRED 属预期，客户端随后轮询 /ai/retry
            check("interview-first-question-async-ok",
                  status in ("AI_ACTION_REQUIRED", "AWAITING_ANSWER", "IN_PROGRESS"),
                  f"会话状态={status}")

    section("10. 授权撤回后 AI 必须被拦截")
    code, _ = call("DELETE", "/api/ai/consent", token=token)
    check("consent-withdraw", is_2xx(code), f"HTTP {code}")
    code, _ = call("POST", "/api/ai/select-materials-for-job", {
        "jobDescriptionId": job["id"], "includedMaterialIds": material_ids[:1],
        "preferredMaterialIds": [], "excludedMaterialIds": [], "resumeTitle": "撤回后应被拦截"},
        token=token, extra_headers={"Idempotency-Key": f"blocked-{RUN}"})
    check("withdrawn-consent-blocks-ai", code == 403, f"HTTP {code}（期望 403）")

    # 重新授权，使账号保持可用状态，便于登录查看生成结果
    code, _ = call("POST", "/api/ai/consent", {
        "policyVersion": "v1.2.0", "providerCode": "bailian", "taskScopes": scopes,
        "dataCategories": ["CAREER_MATERIAL", "JOB_DESCRIPTION", "PERSONAL_PROFILE", "RESUME"],
        "noticeHash": "remote-ai-smoke-regrant"}, token=token)
    check("consent-regranted-for-inspection", is_2xx(code), f"HTTP {code}")

    return report()


def report():
    elapsed = time.time() - STARTED
    passed = sum(1 for r in RESULTS if r["status"] == "PASS")
    failed = [r for r in RESULTS if r["status"] == "FAIL"]
    blocked = [r for r in RESULTS if r["status"] == "BLOCKED"]
    print("\n" + "=" * 72)
    print(f"AI 全链路测试完成：{passed} 通过 / {len(failed)} 失败 / {len(blocked)} 阻塞   耗时 {elapsed:.0f}s")
    if failed:
        print("\n失败项：")
        for item in failed:
            print(f"  - {item['name']}: {item['detail']}")
    if blocked:
        print("\n阻塞项：")
        for item in blocked:
            print(f"  - {item['name']}: {item['detail']}")
    print("\n测试账号（未清理，可登录查看真实生成结果）：")
    print(f"  {USERNAME} / {PASSWORD}")
    print(f"  登录地址 {BASE}/login")
    if globals().get("ATS_SNAPSHOT"):
        print("\nATS 快照：" + json.dumps(globals()["ATS_SNAPSHOT"], ensure_ascii=False))
    print(json.dumps({"summary": {"passed": passed, "failed": len(failed), "blocked": len(blocked),
                                  "elapsedSeconds": round(elapsed)}, "checks": RESULTS},
                     ensure_ascii=False, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""智历 · 全功能验证套件（第二套，补齐 test_non_ai.py 未覆盖的端点）。

覆盖目标：把 91 个端点里此前未被验证的部分尽量补齐 ——
账号与会话全生命周期、职业资料/JD 完整 CRUD、简历版本归档·恢复·当前版本、
评分与 ATS、导出重试、投递完整 CRUD、沟通模板 CRUD 与草稿、面试全流程、
面试答案资产、个人档案导入建议、AI 任务续办列表、跨账号隔离扩展。

设计原则：
- 只走**确定性路径**（ATS useAi=false、面试用 continue-with-rules），不额外消耗模型配额；
- 对"预期会拒绝"的端点不硬断言，而是**记录真实行为**再人工判断（observe）；
- 所有 enum 取值均取自源码，不猜。
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import quote

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8088").rstrip("/")
RUN = uuid.uuid4().hex[:8]
RESULTS = []
OBSERVED = []
SKIPPED = []


def call(method, path, body=None, tok=None, extra=None, timeout=90, raw=None, ctype=None):
    headers = {"Accept": "*/*"}
    data = None
    if raw is not None:
        data = raw
        if ctype:
            headers["Content-Type"] = ctype
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if tok:
        headers["Authorization"] = "Bearer " + tok
    if extra:
        headers.update(extra)
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return 0, str(e).encode()


def data_of(raw):
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
    return bool(ok)


def observe(name, code, raw):
    body = raw.decode("utf-8", "replace")[:220]
    OBSERVED.append((name, code, body))
    print(f"  [观测] {name}  -- HTTP {code} | {body}", flush=True)


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


def main():
    print(f"目标: {BASE}\n批次: {RUN}", flush=True)

    # ---------------- A. 账号与会话全生命周期 ----------------
    sec("A. 账号与会话全生命周期")
    ua, ub = f"full{RUN}a", f"full{RUN}b"
    pwd1, pwd2 = f"Full-{RUN}-1!", f"Full-{RUN}-2!"
    code, raw = call("POST", "/api/auth/register",
                     {"username": ua, "email": f"{ua}@example.invalid", "password": pwd1})
    tok_a = (data_of(raw) or {}).get("accessToken")
    check("A1 register", bool(tok_a), f"HTTP {code}")
    code, raw = call("POST", "/api/auth/register",
                     {"username": ub, "email": f"{ub}@example.invalid", "password": pwd1})
    tok_b = (data_of(raw) or {}).get("accessToken")
    check("A2 register#2", bool(tok_b), f"HTTP {code}")
    if not tok_a or not tok_b:
        return report()

    code, raw = call("POST", "/api/auth/login", {"username": ua, "password": pwd1})
    check("A3 login", code == 200 and (data_of(raw) or {}).get("accessToken"), f"HTTP {code}")

    code, raw = call("POST", "/api/auth/refresh", {}, tok=tok_a)
    refreshed = (data_of(raw) or {}).get("accessToken")
    observe("A4 refresh（凭据来自 Cookie 还是 Body 由实现决定）", code, raw)

    code, raw = call("PATCH", "/api/auth/me", {"displayName": "全功能验证"}, tok=tok_a)
    code2, raw2 = call("GET", "/api/auth/me", tok=tok_a)
    check("A5 PATCH /me 生效", code in (200, 204) and (data_of(raw2) or {}).get("displayName") == "全功能验证",
          f"PATCH HTTP {code} → GET 显示名={(data_of(raw2) or {}).get('displayName')}")

    new_email = f"changed-{RUN}@example.invalid"
    code, raw = call("POST", "/api/auth/me/email", {"email": new_email, "currentPassword": pwd1}, tok=tok_a)
    code2, raw2 = call("GET", "/api/auth/me", tok=tok_a)
    check("A6 改邮箱并即时生效", code in (200, 204) and (data_of(raw2) or {}).get("email") == new_email,
          f"POST HTTP {code} → GET email={(data_of(raw2) or {}).get('email')}")

    code, raw = call("POST", "/api/auth/me/password",
                     {"currentPassword": pwd1, "newPassword": pwd2}, tok=tok_a)
    check("A7 改密码", code in (200, 204), f"HTTP {code}")
    code_new, _ = call("POST", "/api/auth/login", {"username": ua, "password": pwd2})
    code_old, _ = call("POST", "/api/auth/login", {"username": ua, "password": pwd1})
    check("A8 新密码可登录且旧密码失效", code_new == 200 and code_old in (400, 401),
          f"新密码 HTTP {code_new} / 旧密码 HTTP {code_old}")

    code, raw = call("POST", "/api/auth/logout", {}, tok=tok_a)
    check("A9 logout", code in (200, 204), f"HTTP {code}")
    code, raw = call("POST", "/api/auth/logout-all", {}, tok=tok_a)
    observe("A10 logout-all（撤销全部刷新会话）", code, raw)

    # 改密码后旧 token 应已失效（撤销刷新会话）；用最新 token 继续
    code, raw = call("POST", "/api/auth/login", {"username": ua, "password": pwd2})
    tok_a = (data_of(raw) or {}).get("accessToken")
    check("A11 重新登录取新 token", bool(tok_a), f"HTTP {code}")

    # ---------------- B. 职业资料 CRUD ----------------
    sec("B. 职业资料完整 CRUD")
    code, raw = call("POST", "/api/career-materials",
                     {"materialType": "WORK_EXPERIENCE", "title": f"验证岗位-{RUN}",
                      "contentJson": {"organization": "示例科技", "role": "后端工程师"},
                      "sourceText": "负责服务端开发"}, tok=tok_a)
    mid = (data_of(raw) or {}).get("id")
    check("B1 create", bool(mid), f"HTTP {code} id={mid}")
    if mid:
        code, raw = call("GET", f"/api/career-materials/{mid}", tok=tok_a)
        check("B2 get by id", code == 200 and (data_of(raw) or {}).get("id") == mid, f"HTTP {code}")
        code, raw = call("PATCH", f"/api/career-materials/{mid}",
                         {"title": f"验证岗位-改-{RUN}", "contentJson": {"organization": "示例科技"},
                          "sourceText": "负责服务端开发"}, tok=tok_a)
        code2, raw2 = call("GET", f"/api/career-materials/{mid}", tok=tok_a)
        check("B3 patch 生效", code in (200, 204) and (data_of(raw2) or {}).get("title") == f"验证岗位-改-{RUN}",
              f"PATCH HTTP {code} → title={(data_of(raw2) or {}).get('title')}")
        code, raw = call("GET", "/api/career-materials/search?query=Java", tok=tok_a)
        check("B4 search", code == 200, f"HTTP {code}")
        code, raw = call("DELETE", f"/api/career-materials/{mid}", tok=tok_a)
        code2, raw2 = call("GET", f"/api/career-materials/{mid}", tok=tok_a)
        check("B5 delete 后不可读", code in (200, 204) and code2 == 404, f"DELETE {code} → GET {code2}")

    # ---------------- C. JD 完整 ----------------
    sec("C. JD 完整生命周期")
    code, raw = call("POST", "/api/jobs",
                     {"title": f"后端工程师-{RUN}", "companyName": "示例公司", "jdText": JD_TEXT}, tok=tok_a)
    jid = (data_of(raw) or {}).get("id")
    check("C1 create", bool(jid), f"HTTP {code} id={jid}")
    if jid:
        code, raw = call("GET", f"/api/jobs/{jid}", tok=tok_a)
        check("C2 get", code == 200, f"HTTP {code}")
        code, raw = call("POST", f"/api/jobs/{jid}/parse", {}, tok=tok_a)
        # 注意：/parse 返回的是 JobDescriptionDetail（不是 ParsedKeywordsResponse），
        # 关键词落在 parsedKeywordsJson = {"version": "...", "data": {role, keywords, requirements}}
        parsed = (data_of(raw) or {}).get("parsedKeywordsJson") or {}
        kw = (parsed.get("data") or {}).get("keywords")
        check("C3 parse 解析出关键词并落库", code == 200 and isinstance(kw, list) and len(kw) > 0,
              f"HTTP {code} version={parsed.get('version')} keywords={kw}")
        code, raw = call("GET", f"/api/jobs/{jid}/reference", tok=tok_a)
        check("C4 reference", code == 200, f"HTTP {code} {str(data_of(raw))[:80]}")
        code, raw = call("PATCH", f"/api/jobs/{jid}", {"title": f"后端工程师-改-{RUN}", "companyName": "示例公司",
                                                       "jdText": JD_TEXT}, tok=tok_a)
        check("C5 patch", code in (200, 204), f"HTTP {code}")

    # ---------------- D. 简历与版本（含归档/恢复/当前版本） ----------------
    sec("D. 简历与版本全生命周期")
    code, raw = call("POST", "/api/resumes", {"title": f"全功能验证-{RUN}", "resumeJson": RESUME}, tok=tok_a)
    rid = (data_of(raw) or {}).get("id")
    check("D1 resume create", bool(rid), f"HTTP {code} id={rid}")
    vids = []
    if rid:
        for i in (1, 2):
            # SaveVersionRequest 要求 resumeJson + sourceType 都非空（sourceType 必填，漏传即 400）
            code, raw = call("POST", f"/api/resumes/{rid}/versions",
                             {"resumeJson": {**RESUME, "objective": f"第{i}版目标"},
                              "sourceType": "MANUAL"}, tok=tok_a)
            vid = (data_of(raw) or {}).get("id")
            if vid:
                vids.append(vid)
            else:
                observe(f"D2 保存第 {i} 个版本", code, raw)
        check("D2 保存两个版本", len(vids) == 2, f"vids={vids}")
        code, raw = call("GET", f"/api/resumes/{rid}/versions", tok=tok_a)
        lst = data_of(raw)
        check("D3 版本列表", code == 200 and isinstance(lst, list) and len(lst) >= 2,
              f"HTTP {code} 共 {len(lst) if isinstance(lst, list) else '?'} 个")
        code, raw = call("PUT", f"/api/resumes/{rid}", {"title": f"全功能验证-改-{RUN}"}, tok=tok_a)
        check("D4 resume update", code in (200, 204), f"HTTP {code}")
        if len(vids) >= 2:
            code, raw = call("PATCH", f"/api/resumes/{rid}/current-version", {"versionId": vids[1]}, tok=tok_a)
            code2, raw2 = call("GET", f"/api/resumes/{rid}", tok=tok_a)
            cur = (data_of(raw2) or {}).get("currentVersionId")
            check("D5 切换当前版本", code in (200, 204) and cur == vids[1],
                  f"PATCH HTTP {code} → currentVersionId={cur}（期望 {vids[1]}）")
            # 设计规则：当前版本不能归档（ResumeVersionService 抛 CONFLICT）—— 作为正向断言固化
            code, raw = call("POST", f"/api/resumes/{rid}/versions/{vids[1]}/archive", tok=tok_a)
            check("D5b 归档当前版本被拒（设计规则）", code == 409,
                  f"HTTP {code} {raw.decode('utf-8', 'replace')[:90]}")
            # 归档非当前版本应成功。
            # 注意：列表接口签名是 listByResume(resumeId, archived, userId) —— **默认只返回未归档版本**，
            # 要看归档的必须显式传 ?archived=true。故断言要分别查两个视图。
            code, raw = call("POST", f"/api/resumes/{rid}/versions/{vids[0]}/archive", tok=tok_a)
            _, raw_norm = call("GET", f"/api/resumes/{rid}/versions", tok=tok_a)
            _, raw_arch = call("GET", f"/api/resumes/{rid}/versions?archived=true", tok=tok_a)
            in_active = [v for v in (data_of(raw_norm) or []) if v.get("id") == vids[0]]
            in_arch = [v for v in (data_of(raw_arch) or []) if v.get("id") == vids[0]]
            check("D6 归档非当前版本（默认视图隐藏 / archived=true 视图可见）",
                  code in (200, 204) and not in_active and in_arch and in_arch[0].get("archivedAt") is not None,
                  f"HTTP {code} 默认视图={'有' if in_active else '无'} archived视图={'有' if in_arch else '无'} "
                  f"archivedAt={in_arch[0].get('archivedAt') if in_arch else '?'}")
            code, raw = call("POST", f"/api/resumes/{rid}/versions/{vids[0]}/unarchive", tok=tok_a)
            _, raw_norm = call("GET", f"/api/resumes/{rid}/versions", tok=tok_a)
            back = [v for v in (data_of(raw_norm) or []) if v.get("id") == vids[0]]
            check("D7 unarchive 后回到默认视图且 archivedAt 清空",
                  code in (200, 204) and back and back[0].get("archivedAt") is None,
                  f"HTTP {code} 默认视图={'有' if back else '无'} archivedAt={back[0].get('archivedAt') if back else '?'}")
            code, raw = call("POST", f"/api/resumes/{rid}/versions/{vids[0]}/restore", tok=tok_a)
            check("D8 restore 恢复为新版本", code in (200, 201), f"HTTP {code}")
        if jid:
            code, raw = call("GET", f"/api/resumes/by-jd/{jid}", tok=tok_a)
            check("D9 by-jd 查询", code == 200, f"HTTP {code}")

    # ---------------- E. 评分与 ATS ----------------
    sec("E. 评分与 ATS")
    rv = vids[1] if len(vids) >= 2 else None
    if rv and jid:
        code, raw = call("POST", "/api/scoring/match",
                         {"resumeVersionId": rv, "jobDescriptionId": jid}, tok=tok_a)
        res = data_of(raw) or {}
        # 响应字段是 matchResultId（不是 id）
        srid = res.get("matchResultId")
        check("E1 scoring/match", code == 200 and srid is not None,
              f"HTTP {code} matchResultId={srid} 总分={res.get('totalScore')}")
        if srid:
            code, raw = call("GET", f"/api/scoring/results/{srid}", tok=tok_a)
            check("E2 scoring 结果回读", code == 200, f"HTTP {code}")
        code, raw = call("POST", "/api/ats/check",
                         {"resumeVersionId": rv, "jobDescriptionId": jid, "useAi": False}, tok=tok_a,
                         extra={"Idempotency-Key": f"full-ats-{RUN}"})
        ats = data_of(raw) or {}
        aid = ats.get("id")
        check("E3 ats/check（纯规则）", code == 200 and aid is not None,
              f"HTTP {code} totalScore={ats.get('totalScore')} checks={ats.get('checks')}")
        if aid:
            code, raw = call("GET", f"/api/ats/checks/{aid}", tok=tok_a)
            check("E4 ats 结果回读", code == 200, f"HTTP {code}")
            code, raw = call("POST", f"/api/ats/checks/{aid}/ai-retry", {}, tok=tok_a)
            observe("E5 ats ai-retry（对非失败检查的预期行为）", code, raw)

    # ---------------- F. 导出 ----------------
    sec("F. PDF 导出（含任务查询与重试端点）")
    if rv:
        code, raw = call("POST", "/api/exports/pdf", {"resumeVersionId": rv, "templateCode": "classic"}, tok=tok_a)
        t = data_of(raw) or {}
        tid = t.get("taskId") or t.get("id")
        st = None
        for _ in range(30):
            time.sleep(2)
            _, tp = call("GET", f"/api/exports/tasks/{tid}", tok=tok_a)
            st = (data_of(tp) or {}).get("status")
            if st in ("SUCCESS", "FAILED"):
                break
        check("F1 导出任务成功", st == "SUCCESS", f"taskId={tid} status={st}")
        if st == "SUCCESS":
            code, body = call("GET", f"/api/exports/files/{tid}", tok=tok_a)
            check("F2 下载为合法 PDF", code == 200 and body[:5] == b"%PDF-",
                  f"HTTP {code} 字节={len(body)}")
            code, raw = call("POST", f"/api/exports/tasks/{tid}/retry", {}, tok=tok_a)
            observe("F3 export retry（对已成功任务的预期行为）", code, raw)

    # ---------------- G. 投递完整 ----------------
    sec("G. 投递完整 CRUD")
    if rv and jid:
        code, raw = call("POST", "/api/applications",
                         {"jobDescriptionId": jid, "resumeVersionId": rv, "status": "DRAFT",
                          "coverLetterText": "中文求职信"}, tok=tok_a)
        app = data_of(raw) or {}
        apid = app.get("id")
        check("G1 create", code in (200, 201) and bool(apid), f"HTTP {code} id={apid}")
        if apid:
            ver = app.get("version", 0)
            code, raw = call("PUT", f"/api/applications/{apid}",
                             {"jobDescriptionId": jid, "resumeVersionId": rv, "status": "DRAFT",
                              "coverLetterText": "更新后的求职信", "version": ver}, tok=tok_a)
            check("G2 update（乐观锁 version）", code == 200, f"HTTP {code}")
            code, raw = call("PATCH", f"/api/applications/{apid}/status",
                             {"status": "APPLIED", "version": ver + 1}, tok=tok_a)
            observe("G3 status 迁移", code, raw)
            code, raw = call("GET", "/api/applications/stats", tok=tok_a)
            check("G4 stats", code == 200 and (data_of(raw) or {}).get("total") is not None,
                  f"HTTP {code} total={(data_of(raw) or {}).get('total')}")
            code, raw = call("DELETE", f"/api/applications/{apid}", tok=tok_a)
            check("G5 delete", code in (200, 204), f"HTTP {code}")

    # ---------------- H. 沟通模板 CRUD 与草稿 ----------------
    sec("H. 沟通模板与草稿")
    code, raw = call("POST", "/api/communications/templates",
                     {"name": f"验证模板-{RUN}", "scene": "FOLLOW_UP", "type": "EMAIL",
                      "bodyText": "您好，跟进一下进展。", "description": "验证用"}, tok=tok_a)
    tpl = data_of(raw) or {}
    tpid = tpl.get("id")
    check("H1 模板创建", code in (200, 201) and bool(tpid), f"HTTP {code} id={tpid}")
    if tpid:
        code, raw = call("GET", f"/api/communications/templates/{tpid}", tok=tok_a)
        check("H2 模板详情", code == 200, f"HTTP {code}")
        code, raw = call("PUT", f"/api/communications/templates/{tpid}",
                         {"name": f"验证模板-改-{RUN}", "scene": "FOLLOW_UP", "type": "EMAIL",
                          "bodyText": "您好，更新后的跟进内容。", "description": "验证用"}, tok=tok_a)
        check("H3 模板更新", code == 200, f"HTTP {code}")
        code, raw = call("GET", f"/api/communications/templates/{tpid}/preview?resumeVersionId={rv}&jobDescriptionId={jid}",
                         tok=tok_a)
        check("H4 模板预览（需必填 resumeVersionId + jobDescriptionId）", code == 200,
              f"HTTP {code} {str(data_of(raw))[:90]}")
        if rv and jid:
            code, raw = call("POST", "/api/communications/drafts",
                             {"resumeVersionId": rv, "jobDescriptionId": jid, "type": "EMAIL",
                              "draftText": "这是一封待保存的沟通草稿。", "templateId": tpid}, tok=tok_a)
            check("H5 保存草稿", code in (200, 201), f"HTTP {code}")
        code, raw = call("DELETE", f"/api/communications/templates/{tpid}", tok=tok_a)
        code2, _ = call("GET", f"/api/communications/templates/{tpid}", tok=tok_a)
        check("H6 模板删除后不可读", code in (200, 204) and code2 == 404, f"DELETE {code} → GET {code2}")

    # ---------------- I. 面试全流程（确定性规则路径） ----------------
    sec("I. 模拟面试全流程（start → answer → continue-with-rules → finish → report）")
    # 前置：授予 AI 授权 —— 面试启动会派发 AI 任务，未授权时被产品门禁拒绝（这是设计如此，
    # 首次运行没授权导致 start 返回 400，属测试缺前置条件而非缺陷）
    code, raw = call("POST", "/api/ai/consent",
                     {"policyVersion": "v1.2.0", "providerCode": "bailian",
                      "taskScopes": ["INTERVIEW_COACH", "JOB_GENERATION", "ATS_ANALYSIS",
                                     "COMMUNICATION_GENERATE", "INLINE_OPTIMIZE",
                                     "ACHIEVEMENT_GUIDANCE", "JOB_MATERIAL_SELECTION"],
                      # ⚠ INTERVIEW_COACH 在能力注册表里要求的类别是 ["RESUME", "INTERVIEW_ANSWER"]
                      #   （见 AiTaskCapabilityRegistry）——漏掉 INTERVIEW_ANSWER 会让面试的 AI 尝试
                      #   以 FORBIDDEN 失败、会话停在 AI_ACTION_REQUIRED。
                      "dataCategories": ["CAREER_MATERIAL", "JOB_DESCRIPTION", "PERSONAL_PROFILE",
                                         "RESUME", "INTERVIEW_ANSWER"],
                      "noticeHash": f"full-functional-{RUN}"}, tok=tok_a)
    check("I0 授予 AI 授权（面试前置，须含 INTERVIEW_ANSWER）", code in (200, 201), f"HTTP {code}")
    if rv:
        # ⚠ /interviews/start 与 /{id}/follow-up 都有**必填请求头** Idempotency-Key（@NotBlank @Size(max=64)），
        #   漏传即 400（缺必填头 → 400，而非 500）。首次运行没带 → 全部 400，属测试缺前置。
        code, raw = call("POST", "/api/interviews/start",
                         {"sourceType": "PLATFORM_RESUME", "resumeVersionId": rv,
                          "jobDescriptionId": jid, "interviewMode": "COMPREHENSIVE",
                          "targetQuestionCount": 4, "outputLanguage": "ZH_CN"}, tok=tok_a,
                         extra={"Idempotency-Key": f"iv-start-{RUN}"})
        iv = data_of(raw) or {}
        # 响应字段是 interviewId（不是 id / sessionId）
        sid = iv.get("interviewId") or iv.get("id") or iv.get("sessionId")
        if not sid:
            observe("I1 启动面试失败原因", code, raw)
        check("I1 启动面试（需必填 Idempotency-Key 头）", code == 200 and bool(sid),
              f"HTTP {code} interviewId={sid} status={iv.get('status')} executionMode={iv.get('executionMode')}")
        # CI 环境无模型密钥时，首题生成会确定性失败（aiFailure.messageCode 非 None）。
        # 此时面试全流程无法继续 → **显式 SKIP 并如实标注**，不得计为失败、也不得视为已验证
        # （QA 纪律：跳过必须在结果中显式呈现，不得视为通过）。
        ai_unavailable = iv.get("status") == "AI_ACTION_REQUIRED" and bool(iv.get("aiFailure"))
        if ai_unavailable:
            message_code = (iv.get("aiFailure") or {}).get("messageCode")
            print(f"  [SKIP] I2~I6 面试全流程 —— 目标环境无可用模型（aiFailure.messageCode={message_code}）。"
                  "该路径本轮未验证，不得视为通过。", flush=True)
            SKIPPED.append(f"I 面试全流程（AI 不可用，messageCode={message_code}）")
        elif sid:
            # 面试状态机：InterviewStatus = GENERATING_QUESTION / AWAITING_ANSWER /
            # EVALUATING_ANSWER / AI_ACTION_REQUIRED / COMPLETED。
            # AI 模式下启动后处于 AI_ACTION_REQUIRED（等 AI 异步出题），
            # 只有状态变为 AWAITING_ANSWER 才允许 /answer（否则 409「当前不在等待回答状态」）。
            def wait_until(sid_, want, max_sec=180, step=3):
                waited, st, dd = 0, None, {}
                while waited < max_sec:
                    c, r = call("GET", f"/api/interviews/{sid_}", tok=tok_a)
                    dd = data_of(r) or {}
                    st = dd.get("status")
                    if st in want:
                        return st, dd, waited
                    time.sleep(step)
                    waited += step
                return st, dd, waited

            st, dd, waited = wait_until(sid, {"AWAITING_ANSWER", "COMPLETED"}, 180)
            if st != "AWAITING_ANSWER":
                # 关键：AI 失败原因应**在状态响应里可见**（字段 aiFailure），而非静默
                observe("I2 未达 AWAITING_ANSWER 时的 aiFailure", 200,
                        json.dumps({"status": st, "aiFailure": dd.get("aiFailure"),
                                    "completed": dd.get("completedQuestionCount")},
                                   ensure_ascii=False).encode())
            check("I2 等待 AI 异步生成首题 → AWAITING_ANSWER", st == "AWAITING_ANSWER",
                  f"status={st} 等待={waited}s 题目数={dd.get('completedQuestionCount')}/{dd.get('targetQuestionCount')}")
            rounds, finished = 0, False
            for i in range(6):
                if st != "AWAITING_ANSWER":
                    break
                # /answer 亦要求必填头 Idempotency-Key；**每轮换新键**，否则会被幂等去重
                code, raw = call("POST", f"/api/interviews/{sid}/answer",
                                 {"answer": f"第 {i + 1} 轮回答：我负责订单服务的设计与性能优化，"
                                            f"通过缓存与慢查询治理把 P99 降低约三分之一。"},
                                 tok=tok_a, extra={"Idempotency-Key": f"iv-ans-{RUN}-{i}"})
                if code not in (200, 201):
                    observe(f"I3 answer #{i + 1}", code, raw)
                    break
                rounds += 1
                st, dd, w = wait_until(sid, {"AWAITING_ANSWER", "COMPLETED"}, 180)
                done, target = dd.get("completedQuestionCount"), dd.get("targetQuestionCount")
                print(f"    · 第 {rounds} 轮完成（等待 {w}s）→ status={st} 题目 {done}/{target}", flush=True)
                if st == "COMPLETED" or (isinstance(done, int) and isinstance(target, int) and done >= target):
                    finished = True
                    break
            check("I3 面试逐轮推进（answer → 等评估 → 下一题）", rounds > 0 and (finished or st in ("AWAITING_ANSWER", "COMPLETED")),
                  f"完成 {rounds} 轮，最终 status={st}")
            code, raw = call("POST", f"/api/interviews/{sid}/finish", {}, tok=tok_a)
            if code not in (200, 201):
                observe("I4 finish", code, raw)
            check("I4 finish 结束面试", code in (200, 201), f"HTTP {code}")
            code, raw = call("GET", f"/api/interviews/{sid}/report", tok=tok_a)
            rep = data_of(raw) or {}
            check("I5 报告可读", code == 200 and bool(rep), f"HTTP {code} 字段={list(rep)[:8]}")
            code, raw = call("POST", f"/api/interviews/{sid}/follow-up",
                             {"weakness": "高并发场景回答偏浅"}, tok=tok_a,
                             extra={"Idempotency-Key": f"iv-followup-{RUN}"})
            observe("I6 follow-up 练习（依赖 AI，同样需必填头）", code, raw)

    # ---------------- J. 面试答案资产 ----------------
    sec("J. 面试答案资产 CRUD")
    code, raw = call("POST", "/api/interview-answer-assets",
                     {"questionText": "请介绍一次性能优化经历",
                      "originalAnswerText": "我在订单服务中引入缓存与慢查询治理。",
                      "suggestedAnswerText": "建议补充量化结果与个人贡献。",
                      "sectionKeys": ["work"]}, tok=tok_a)
    asid = (data_of(raw) or {}).get("id")
    check("J1 创建资产", code in (200, 201) and bool(asid), f"HTTP {code} id={asid}")
    if asid:
        code, raw = call("GET", "/api/interview-answer-assets", tok=tok_a)
        check("J2 列表", code == 200 and isinstance(data_of(raw), list), f"HTTP {code}")
        code, raw = call("GET", "/api/interview-answer-assets?keyword=" + quote("性能"), tok=tok_a)
        check("J3 关键词筛选（中文需 URL 编码）", code == 200, f"HTTP {code} 命中={len(data_of(raw) or [])}")
        code, raw = call("PUT", f"/api/interview-answer-assets/{asid}",
                         {"questionText": "请介绍一次性能优化经历（已更新）",
                          "originalAnswerText": "我在订单服务中引入缓存与慢查询治理。",
                          "suggestedAnswerText": "建议补充量化结果、个人贡献与验证方式。",
                          "sectionKeys": ["work"]}, tok=tok_a)
        check("J4 更新", code == 200, f"HTTP {code}")
        code, raw = call("DELETE", f"/api/interview-answer-assets/{asid}", tok=tok_a)
        check("J5 删除", code in (200, 204), f"HTTP {code}")

    # ---------------- K. 个人档案导入建议 / AI 任务续办 ----------------
    sec("K. 个人档案导入建议 / AI 任务续办列表")
    code, raw = call("GET", f"/api/personal-profile/import-suggestion?resumeId={rid}", tok=tok_a)
    check("K1 导入建议（需必填 resumeId）", code == 200, f"HTTP {code} {str(data_of(raw))[:100]}")
    code, raw = call("GET", "/api/ai/tasks/continuations", tok=tok_a)
    check("K2 AI 任务续办列表", code == 200 and isinstance(data_of(raw), (list, dict)), f"HTTP {code}")

    # ---------------- L. 跨账号隔离（扩展到新资源） ----------------
    sec("L. 跨账号隔离（B 读 A 的新资源）")
    if rid:
        for name, path in [("简历", f"/api/resumes/{rid}"),
                           ("简历版本列表", f"/api/resumes/{rid}/versions"),
                           ("JD", f"/api/jobs/{jid}"),
                           ("投递", "/api/applications"),
                           ("面试答案资产", "/api/interview-answer-assets")]:
            code, _, = call("GET", path, tok=tok_b)
            if name == "投递" or name == "面试答案资产":
                lst = data_of(call("GET", path, tok=tok_b)[1])
                check(f"L {name} 归属隔离（B 看到 0 条）", isinstance(lst, list) and len(lst) == 0,
                      f"B 可见 {len(lst) if isinstance(lst, list) else '?'} 条")
            else:
                check(f"L {name} 归属隔离（404）", code == 404, f"B 访问 → HTTP {code}")

    # ---------------- M. 删除账号（一次性账号） ----------------
    sec("M. 删除账号")
    utmp = f"tmp{RUN}"
    code, raw = call("POST", "/api/auth/register",
                     {"username": utmp, "email": f"{utmp}@example.invalid", "password": pwd1})
    ttmp = (data_of(raw) or {}).get("accessToken")
    if ttmp:
        code, raw = call("DELETE", "/api/auth/me", tok=ttmp)
        code2, raw2 = call("POST", "/api/auth/login", {"username": utmp, "password": pwd1})
        # 登录被拒即为正确（实现返回 403 —— 账号已 DISABLED，属合理的拒绝码，非 400/401）
        check("M1 删除账号后无法登录", code in (200, 204) and 400 <= code2 < 500,
              f"DELETE {code} → login {code2}（拒绝即正确）")
        code3, _ = call("GET", "/api/auth/me", tok=ttmp)
        check("M2 删除账号后旧 token 失效", code3 == 401, f"旧 token → HTTP {code3}")

    return report()


def report():
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[1]]
    print("\n" + "=" * 66)
    print(f"断言合计 {total} 项，通过 {total - len(failed)}，失败 {len(failed)}"
          + (f"，跳过 {len(SKIPPED)}" if SKIPPED else ""))
    if failed:
        print("失败项：")
        for n, _ in failed:
            print(f"  - {n}")
    if SKIPPED:
        print("跳过项（本轮未验证，不得视为通过）：")
        for s in SKIPPED:
            print(f"  - {s}")
    if OBSERVED:
        print(f"\n需人工判断的端点行为（{len(OBSERVED)} 条）：")
        for n, c, b in OBSERVED:
            print(f"  · {n} → HTTP {c} | {b[:150]}")
    print("=" * 66, flush=True)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())

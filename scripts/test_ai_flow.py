#!/usr/bin/env python3
"""智历 AI 功能端到端测试脚本"""

import urllib.request
import urllib.error
import json
import time
from datetime import datetime

BASE = "http://localhost:8080"
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJpbnRlbGxpZ2VudC1yZXN1bWUtYnVpbGRlciIsInN1YiI6Ijc0IiwidXNlcm5hbWUiOiJ0ZXN0dXNlcjEiLCJpYXQiOjE3ODQ4NTUwODcsImV4cCI6MTc4NDg1ODY4N30.rMRNmFnGckoPIZ-QzWazGqlbuKOoaUxM-q6gDFA-X4s"


def call(method, path, body=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode('utf-8') if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', errors='replace')
        try:
            return e.code, json.loads(body)
        except json.JSONDecodeError:
            return e.code, {"error": body}


def ok(resp):
    code, data = resp
    if code not in (200, 201, 202):
        raise RuntimeError(f"HTTP {code}: {json.dumps(data, ensure_ascii=False)}")
    return data


def log(tag, msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {tag}: {msg}")


# ========== Step 1: 创建职业资料（多条） ==========
log("STEP1", "创建职业资料...")
m1 = ok(call("POST", "/api/career-materials", {
    "materialType": "WORK_EXPERIENCE",
    "title": "字节跳动后端开发工程师",
    "contentJson": {
        "company": "字节跳动",
        "position": "后端开发工程师",
        "startDate": "2022-03",
        "endDate": "2024-06",
        "description": "负责用户增长平台后端服务开发，日均处理10亿+请求",
        "highlights": ["主导微服务架构迁移", "设计分布式id生成器", "引入Redis缓存层"]
    }
}))
log("STEP1", f"  工作经历: id={m1['data']['id']}, title={m1['data']['title']}")

m2 = ok(call("POST", "/api/career-materials", {
    "materialType": "SKILL",
    "title": "Java",
    "contentJson": {"name": "Java", "level": "精通", "years": 5}
}))
log("STEP1", f"  技能: id={m2['data']['id']}, title={m2['data']['title']}")

m3 = ok(call("POST", "/api/career-materials", {
    "materialType": "SKILL",
    "title": "Spring Boot",
    "contentJson": {"name": "Spring Boot", "level": "精通", "years": 4}
}))
log("STEP1", f"  技能: id={m3['data']['id']}, title={m3['data']['title']}")

m4 = ok(call("POST", "/api/career-materials", {
    "materialType": "PROJECT_EXPERIENCE",
    "title": "电商平台微服务改造",
    "contentJson": {
        "name": "电商平台微服务改造",
        "role": "核心开发",
        "description": "将单体电商系统拆分为12个微服务，QPS提升300%"
    }
}))
log("STEP1", f"  项目: id={m4['data']['id']}, title={m4['data']['title']}")

m5 = ok(call("POST", "/api/career-materials", {
    "materialType": "EDUCATION",
    "title": "计算机科学本科",
    "contentJson": {
        "school": "清华大学",
        "degree": "本科",
        "major": "计算机科学与技术",
        "graduationYear": "2022"
    }
}))
log("STEP1", f"  教育: id={m5['data']['id']}, title={m5['data']['title']}")

material_ids = [
    m1['data']['id'], m2['data']['id'], m3['data']['id'],
    m4['data']['id'], m5['data']['id']
]
log("STEP1", f"共计创建 {len(material_ids)} 条职业资料: {material_ids}")

# ========== Step 2: 创建 JD ==========
log("STEP2", "创建岗位描述...")
jd = ok(call("POST", "/api/jobs", {
    "title": "高级Java开发工程师",
    "companyName": "阿里巴巴",
    "jdText": """【岗位职责】
1. 负责电商核心交易链路的设计与开发
2. 参与高并发分布式系统架构设计
3. 负责系统性能优化和稳定性保障

【任职要求】
1. 统招本科及以上学历，计算机相关专业
2. 3年以上Java开发经验，精通Spring Boot/MyBatis
3. 熟悉微服务架构，有分布式系统设计经验
4. 熟悉MySQL、Redis等中间件
5. 有高并发场景开发经验者优先"""
}))
jd_id = jd['data']['id']
log("STEP2", f"  JD: id={jd_id}, title={jd['data']['title']}")

# ========== Step 3: 创建简历 ==========
log("STEP3", "创建基础简历...")
resume = ok(call("POST", "/api/resumes", {
    "title": "我的高级Java简历",
    "contentJson": {
        "basicInfo": {
            "name": "张三",
            "email": "zhangsan@example.com",
            "phone": "13800138000"
        }
    }
}))
resume_id = resume['data']['id']
log("STEP3", f"  简历: id={resume_id}, title={resume['data']['title']}")

# ========== Step 4: AI 岗位定制生成 ==========
log("STEP4", "发起AI岗位定制生成...")
gen_resp = ok(call("POST", "/api/ai/generate-resume-for-job", {
    "taskType": "JOB_GENERATION",
    "jobDescriptionId": jd_id,
    "targetResumeId": resume_id,
    "input": {
        "includedMaterialIds": material_ids,
        "preferredMaterialIds": [],
        "excludedMaterialIds": []
    }
}))
task_id = gen_resp['data']['id']
task_status = gen_resp['data']['status']
log("STEP4", f"  AI任务: id={task_id}, status={task_status}")

# ========== Step 5: 轮询任务状态 ==========
log("STEP5", "轮询任务状态...")
max_wait = 60
for i in range(max_wait // 3):
    time.sleep(3)
    status_resp = ok(call("GET", f"/api/ai/tasks/{task_id}"))
    st = status_resp['data']['status']
    log("STEP5", f"  轮询 #{i+1}: status={st}")
    if st in ("COMPLETED", "FAILED"):
        log("STEP5", f"最终状态: {st}")
        if st == "COMPLETED":
            result = status_resp['data'].get('result', {})
            log("STEP5", f"生成结果: {json.dumps(result, ensure_ascii=False, indent=2)[:500]}")
        break
else:
    log("STEP5", "⚠ 超时，任务可能仍在处理中")

# ========== Step 6: 检查评分接口 ==========
log("STEP6", "检查评分接口可用性...")
try:
    score_resp = call("POST", "/api/scoring/match", {
        "jobDescriptionId": jd_id,
        "resumeId": resume_id
    })
    if score_resp[0] in (200, 201, 202):
        score_data = score_resp[1]
        if 'data' in score_data and score_data['data']:
            log("STEP6", f"评分结果: score={score_data['data'].get('overallScore', 'N/A')}")
        else:
            log("STEP6", f"评分响应: {json.dumps(score_data, ensure_ascii=False)[:200]}")
    else:
        log("STEP6", f"评分接口返回 {score_resp[0]}: {json.dumps(score_resp[1], ensure_ascii=False)[:200]}")
except Exception as e:
    log("STEP6", f"评分接口错误: {e}")

# ========== Summary ==========
print("\n" + "=" * 60)
print("AI 功能测试完成")
print(f"  职业资料: {len(material_ids)} 条 (IDs: {material_ids})")
print(f"  岗位JD:   id={jd_id}")
print(f"  基础简历: id={resume_id}")
print(f"  AI任务:   id={task_id}")
print("=" * 60)

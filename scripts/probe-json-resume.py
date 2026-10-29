"""B4 线上实测：JSON Resume v1.0.0 导出（一次性探针，跑完自清理）。

断言的是「线上真实返回」，不是单测里的构造值：
  1. 附件形态（Content-Type / Content-Disposition）而不是 ApiResponse 信封；
  2. 根上只有官方 14 个标准键（schema 是 additionalProperties:false，多一个键就非法）；
  3. 任何层级都没有 null（官方 schema 里 null 会打断第三方解析）；
  4. 平台自有内容进了 meta.platform，未知顶层键进了 meta.platform.unknownSections（不静默丢弃）；
  5. 非 iso8601 的日期被省略而不是伪造；
  6. 他人版本 404，不泄露存在性。
"""

import json
import os
import random
import string
import sys
import urllib.error
import urllib.request

BASE = os.environ.get('BASE', 'http://101.35.239.218:8088')
FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'test-fixtures', 'resume-all-sections.json')
STANDARD_ROOT = {
    '$schema', 'basics', 'work', 'volunteer', 'education', 'awards', 'certificates',
    'publications', 'skills', 'languages', 'interests', 'references', 'projects', 'meta',
}

failures = []


def check(name, ok, detail=''):
    print(('  PASS  ' if ok else '  FAIL  ') + name + ('' if ok else ' -> ' + str(detail)))
    if not ok:
        failures.append(name)


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as err:
        return err.code, dict(err.headers), err.read()


def envelope(method, path, body=None, token=None):
    status, _, payload = call(method, path, body, token)
    try:
        parsed = json.loads(payload)
    except ValueError:
        return status, None
    return status, parsed


def rand(prefix, n=8):
    return prefix + ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(n))


def find_nulls(node, path='$'):
    out = []
    if node is None:
        out.append(path)
    elif isinstance(node, dict):
        for k, v in node.items():
            out.extend(find_nulls(v, path + '.' + str(k)))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            out.extend(find_nulls(v, '%s[%d]' % (path, i)))
    return out


def register_and_login():
    """注册即登录：`POST /api/auth/register` 返回 201 且**直接带回 accessToken**。

    不要再顺手调一次 `/api/auth/login` —— 那会掩盖「注册是否真的下发了可用令牌」，
    也会让探针在注册成功但登录接口坏掉时误报成通过。
    """
    user = rand('probe_json_')
    email = user + '@example.com'
    password = 'Probe#2026'
    status, body = envelope('POST', '/api/auth/register', {'username': user, 'email': email, 'password': password})
    assert status in (200, 201) and body.get('code') == 0, ('register', status, body)
    token = (body.get('data') or {}).get('accessToken')
    assert token, ('register-no-token', body)
    return user, token


def main():
    with open(FIXTURE, encoding='utf-8') as handle:
        fixture = json.load(handle)
    # 夹具带 `unknownFutureField`：它是**历史/导入数据**才可能出现的键。写入路径的 schema
    # 校验会直接拒绝它，所以线上永远造不出「未知顶层键」的简历——这一点本身值得断言（见下）。
    resume_json = {k: v for k, v in fixture.items() if k != 'unknownFutureField'}

    print('== 造数据 ==')
    user, token = register_and_login()
    check('注册直接下发 accessToken', bool(token), token)

    status, body = envelope('POST', '/api/resumes',
                            {'title': 'JSON Resume 探针', 'resumeJson': fixture}, token)
    check('写入未知顶层键被拒（40001）',
          status == 400 and (body or {}).get('code') == 40001, (status, body))

    status, body = envelope('POST', '/api/resumes', {'title': 'JSON Resume 探针', 'resumeJson': resume_json}, token)
    check('创建简历 2xx/0', status in (200, 201) and (body or {}).get('code') == 0, (status, body))
    resume_id = body['data']['id'] if 'id' in body.get('data', {}) else body['data'].get('resumeId')

    status, body = envelope('POST', '/api/resumes/%d/versions' % resume_id,
                            {'sourceType': 'MANUAL', 'resumeJson': resume_json}, token)
    check('保存版本 2xx/0', status in (200, 201) and body.get('code') == 0, (status, body))
    version_id = body['data']['id']
    version_no = body['data']['versionNo']
    print('  resumeId=%s versionId=%s versionNo=%s' % (resume_id, version_id, version_no))

    print('== 导出 ==')
    status, headers, payload = call('GET', '/api/resume-versions/%d/json-resume' % version_id, token=token)
    check('导出 200', status == 200, (status, payload[:200]))
    ctype = headers.get('Content-Type', '')
    check('Content-Type 是 JSON', ctype.startswith('application/json'), ctype)
    disposition = headers.get('Content-Disposition', '')
    expected_name = 'resume-%d-v%d-jsonresume.json' % (resume_id, version_no)
    check('以附件下发且文件名可预测',
          disposition.startswith('attachment') and expected_name in disposition,
          (disposition, expected_name))

    try:
        document = json.loads(payload)
    except ValueError as err:
        check('响应体是合法 JSON', False, err)
        return report()

    print('== 结构 ==')
    extra = set(document) - STANDARD_ROOT
    check('根上只有官方标准键', not extra, sorted(extra))
    check('$schema 指向 v1.0.0', 'v1.0.0' in str(document.get('$schema', '')), document.get('$schema'))
    nulls = find_nulls(document)
    check('任何层级都没有 null', not nulls, nulls[:5])

    meta = document.get('meta') or {}
    platform = meta.get('platform') or {}
    # versionNo 以服务端返回为准：新建简历会自动产生 v1，本次保存的是 v2。
    # 断言「导出件的 versionNo == 保存时返回的 versionNo」，而不是写死 1。
    check('meta.platform 保留 resumeId/versionNo/exporter',
          platform.get('resumeId') == resume_id and platform.get('versionNo') == version_no
          and bool(platform.get('exporter')), platform)
    check('objective/courses/customSections 进 meta.platform',
          'objective' in platform and 'courses' in platform and 'customSections' in platform, sorted(platform))
    unknown = platform.get('unknownSections') or {}
    # 线上不可能造出未知顶层键（写入阶段已被 schema 拒），所以这里期望「为空/缺省」；
    # 真正的搬迁逻辑由 `JsonResumeExporterTest` 直接构造入参覆盖——那是唯一能造出该输入的层。
    check('干净数据下 unknownSections 不出现', not unknown, sorted(unknown))

    basics = document.get('basics') or {}
    check('basics 映射 name/email/label', bool(basics.get('name')) and bool(basics.get('email')), basics)
    check('links 映射为 profiles', bool((basics.get('profiles') or [])) and 'url' in basics['profiles'][0], basics.get('profiles'))

    count_key = [k for k in ('work',) if k in document][0]
    check('work 条目数等于夹具', len(document.get(count_key) or []) == len(resume_json.get('work') or []),
          (len(document.get(count_key) or []), len(resume_json.get('work') or [])))
    dates = [item.get('startDate') for item in document.get('work') or [] if item.get('startDate')]
    check('日期只保留 iso8601 形态', all(len(d) in (4, 7, 10) and d[0].isdigit() for d in dates), dates)

    print('== 归属隔离 ==')
    _, other_token = register_and_login()
    status, body = envelope('GET', '/api/resume-versions/%d/json-resume' % version_id, token=other_token)
    check('他人版本返回 404 + 40401', status == 404 and (body or {}).get('code') == 40401, (status, body))

    print('== 清理 ==')
    status, _, _ = call('DELETE', '/api/resumes/%d' % resume_id, token=token)
    check('删除探针简历', status in (200, 204, 404), status)
    return report()


def report():
    print()
    if failures:
        print('结论：%d 项失败 —— %s' % (len(failures), '; '.join(failures)))
        return 1
    print('结论：全部断言通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())

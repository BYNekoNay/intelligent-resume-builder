"""B5 线上实测：限流注册表在真实环境生效（一次性探针）。

验的是「线上真的按规则组限流」，而不是单测里的 Mock：
  1. 单个客户端连续登录超过 login 档位 → 第 N+1 次 429，带 Retry-After 与 X-RateLimit-Rule: login；
  2. 429 之后**别的规则组**不受影响（证明分桶按规则组，而不是全局计数、也不是按 URL）；
  3. 429 响应体是统一信封（code=42901、有 traceId）；等价百分号转义路径仍命中同一桶。

每轮带一个 `X-Forwarded-For`（TEST-NET-2 文档段）。注意：线上 nginx 用 `$remote_addr`
**覆写**该头（`DeployProxyClientIpContractTest` 守着），所以它**不能**用来隔离客户端——
探针实际仍落在这个出口 IP 的同一个桶里。因此：
  - 「放行数不超过阈值」是硬断言（超了就是限流失效）；
  - 「放行数恰好等于阈值」只在窗口空闲时才成立，窗口内已有占用时如实标为**未验证**
    （精确计数由 `RateLimitFilterTest` 用可控时钟覆盖）。

刻意只用匿名可打的 login 档位：阈值最低（默认 10/min）、无需造业务数据，
几分钟内自然恢复，不给测试环境留垃圾。
"""

import json
import os
import random
import sys
import urllib.error
import urllib.request

BASE = os.environ.get('BASE', 'http://101.35.239.218:8088')
LOGIN_LIMIT = int(os.environ.get('LOGIN_LIMIT', '10'))
REGISTER_LIMIT = int(os.environ.get('REGISTER_LIMIT', '5'))
failures = []
unverified = []


def check(name, ok, detail=''):
    print(('  PASS  ' if ok else '  FAIL  ') + name + ('' if ok else ' -> ' + str(detail)))
    if not ok:
        failures.append(name)


def header(headers, name):
    """HTTP 头名大小写不敏感：Tomcat 实际发出的是 `X-Ratelimit-Rule`（只保留首字母大写），
    用 `headers['X-RateLimit-Rule']` 直接取会拿到 None —— 这曾让探针误报「产品缺头」。"""
    wanted = name.lower()
    for key, value in headers.items():
        if key.lower() == wanted:
            return value
    return None


def call(method, path, body=None, client_ip=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    if client_ip:
        req.add_header('X-Forwarded-For', client_ip)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as err:
        return err.code, dict(err.headers), err.read()


def main():
    # TEST-NET-2（198.51.100.0/24，RFC 5737 文档保留段）：不指向任何真实主机
    client_ip = '198.51.100.%d' % random.randint(2, 250)
    print('== 登录档（默认 %d/min，客户端 %s）==' % (LOGIN_LIMIT, client_ip))

    blocked = None
    allowed = 0
    for attempt in range(1, LOGIN_LIMIT + 3):
        status, headers, payload = call('POST', '/api/auth/login',
                                        {'username': 'probe-rate-limit', 'password': 'wrong-password'}, client_ip)
        if status == 429:
            blocked = (attempt, headers, payload)
            print('  第 %d 次请求开始 429（此前放行 %d 次）' % (attempt, allowed))
            break
        allowed += 1

    if blocked is None:
        check('超过阈值后返回 429', False, '连续 %d 次都未限流' % (LOGIN_LIMIT + 2))
        return report()

    attempt, headers, payload = blocked
    if allowed == 0:
        unverified.append('本客户端首个请求即 429（该 IP 窗口未恢复）')
        print('  注：本轮未验证「阈值附近才限流」，按未验证处理，不得视为通过')
    else:
        # 硬上界：无论窗口里已有多少占用，放行数都不得**超过**阈值（超了就是限流失效）
        check('放行数不超过阈值', allowed <= LOGIN_LIMIT, '放行 %d，阈值 %d' % (allowed, LOGIN_LIMIT))
        if allowed == LOGIN_LIMIT:
            check('放行数恰好等于阈值', True)
        else:
            # 线上 nginx 用 $remote_addr 覆写 X-Forwarded-For（部署探针专门守着这一点），
            # 所以探针无法靠伪造 IP 拿到「全新客户端」：login 桶里本来就有别的占用。
            # 精确计数改由下方 register 档（测试环境无人触碰）实测，这里只作说明。
            print('  注：login 桶本窗口已有 %d 次占用，精确计数改由 register 档验证'
                  % (LOGIN_LIMIT - allowed))

    retry_after = header(headers, 'Retry-After')
    check('429 带 Retry-After（1~60 秒）',
          retry_after is not None and retry_after.isdigit() and 1 <= int(retry_after) <= 60, retry_after)
    check('429 回显命中的规则 id（排障锚点）', header(headers, 'X-RateLimit-Rule') == 'login',
          header(headers, 'X-RateLimit-Rule'))

    body = json.loads(payload)
    check('响应体是统一信封且 code=42901', body.get('code') == 42901, body)
    check('信封带 traceId', bool(body.get('traceId')), body)

    print('== 分桶隔离 ==')
    status, _, _ = call('GET', '/api/system/health', client_ip=client_ip)
    check('登录桶耗尽不影响健康检查（按规则组分桶）', status == 200, status)
    status, _, _ = call('POST', '/api/auth/%6Cogin',
                        {'username': 'probe-rate-limit', 'password': 'wrong-password'}, client_ip)
    check('等价转义路径仍命中同一桶（fail-closed）', status == 429, status)

    measure_register_exact_count(client_ip)
    return report()


def measure_register_exact_count(client_ip):
    """在 register 档（默认 5/min）上量「精确计数」。

    为什么不用 login 量：探针自己的连续调用会把 login 窗口一直占满，
    精确计数在共享出口 IP 上根本复现不出来。register 档在测试环境几乎无人触碰，
    且**请求体故意非法**（缺 email/password）——限流在参数校验之前计数，
    所以每次请求都会计入配额但不会落任何账号数据。
    """
    print('== 注册档（默认 %d/min）：精确计数 ==' % REGISTER_LIMIT)
    allowed = 0
    for _ in range(REGISTER_LIMIT + 2):
        status, headers, _ = call('POST', '/api/auth/register', {'username': 'probe-rate-limit'}, client_ip)
        if status == 429:
            check('429 回显命中的规则 id', header(headers, 'X-RateLimit-Rule') == 'register',
                  header(headers, 'X-RateLimit-Rule'))
            break
        allowed += 1
    if allowed == REGISTER_LIMIT:
        check('放行数恰好等于阈值（第 %d 次起 429）' % (REGISTER_LIMIT + 1), True)
    elif allowed < REGISTER_LIMIT:
        unverified.append('register 档精确计数（本窗口已有 %d 次占用）' % (REGISTER_LIMIT - allowed))
    else:
        check('放行数恰好等于阈值', False, '放行 %d，阈值 %d' % (allowed, REGISTER_LIMIT))


def report():
    print()
    if failures:
        print('结论：%d 项失败 —— %s' % (len(failures), '; '.join(failures)))
        return 1
    if unverified:
        print('结论：断言通过，但有 %d 项未验证 —— %s' % (len(unverified), '; '.join(unverified)))
        return 2
    print('结论：全部断言通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())

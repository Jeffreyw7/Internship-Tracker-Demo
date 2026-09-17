"""Real HTTP and restart-persistence smoke test for the bundled Spring Boot JAR.

Run after npm run build and backend/mvnw -Pwith-ui package:
    python tests/http_smoke.py
Uses a temporary H2 database and test account; does not touch user data.
"""
import datetime
import http.cookiejar
import json
import pathlib
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'backend/target/campus-tracker-2.0.0.jar'
assert JAR.exists(), 'Build the with-ui JAR before running this test.'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))


def call(path, method='GET', data=None, headers=None, expected=200, form=False):
    headers = dict(headers or {})
    if data is not None:
        body = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    else:
        body = None
    request = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        response = client.open(request, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}: {raw[:300]!r}'
    return json.loads(raw) if 'application/json' in response.headers.get('Content-Type', '') and raw else raw


def csrf():
    token = call('/api/auth/csrf')
    return {token['headerName']: token['token']}


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


with tempfile.TemporaryDirectory(prefix='campus-http-test-') as temp:
    log_path = pathlib.Path(temp) / 'server.log'
    with log_path.open('w') as log:
        command = ['java', '-jar', str(JAR), f'--server.port={port}', '--server.address=127.0.0.1',
                   f'--spring.datasource.url=jdbc:h2:file:{temp}/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
                   '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false']

        def start():
            process = subprocess.Popen(command, cwd=temp, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(90):
                    if process.poll() is not None:
                        raise RuntimeError('Java exited during startup: ' + log_path.read_text()[-3000:])
                    try:
                        if call('/api/health')['status'] == 'UP':
                            return process
                    except (OSError, AssertionError):
                        time.sleep(0.5)
                raise RuntimeError('Java startup timed out: ' + log_path.read_text()[-3000:])
            except BaseException:
                stop(process)
                raise

        process = start()
        try:
            page = call('/').decode()
            assert 'Campus' in page and 'id="root"' in page
            for path in re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"', page):
                assert len(call(path)) > 100
            call('/api/applications', expected=401)
            identity = {'email': 'http-test@example.test', 'password': 'Temporary-test-123!'}
            call('/api/auth/register', 'POST', {**identity, 'displayName': 'HTTP Tester', 'includeSamples': True}, csrf(), expected=201)
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            assert call('/api/applications')['totalElements'] == 10
            headers = csrf()
            draft = {'company': 'Restart verification', 'role': 'Java Intern', 'location': 'Remote',
                     'status': 'Applied', 'appliedDate': str(datetime.date.today() - datetime.timedelta(days=1)),
                     'deadline': None, 'followUpDate': None, 'notes': 'Saved through real HTTP', 'stages': []}
            app = call('/api/applications', 'POST', draft, headers, expected=201)
            path = '/api/applications/' + app['id']
            draft['notes'] = 'Survives a complete JVM restart'
            updated = call(path, 'PUT', draft, {**headers, 'If-Match': str(app['version'])})
            assert updated['version'] > app['version']
            call(path, 'PUT', draft, {**headers, 'If-Match': str(app['version'])}, expected=409)
            deleted = call(path, 'DELETE', headers={**headers, 'If-Match': str(updated['version'])})
            call(path, expected=404)
            restored = call(path + '/restore', 'POST', headers={**headers, 'If-Match': str(deleted['version'])})
            assert restored['notes'] == draft['notes']
        finally:
            stop(process)

        process = start()
        try:
            call('/api/auth/me', expected=401)
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            persisted = call(path)
            assert persisted['notes'] == 'Survives a complete JVM restart'
            assert call('/api/applications')['totalElements'] == 11
            call('/api/auth/logout', 'POST', headers=csrf(), expected=204)
            call('/api/applications', expected=401)
        finally:
            stop(process)

print('PASS: real HTTP page/assets, authentication, CRUD, CSRF, conflict, delete/undo, logout, and H2 restart persistence.')

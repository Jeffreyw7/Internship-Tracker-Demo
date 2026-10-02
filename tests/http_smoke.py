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
import socketserver
import threading
import queue
import os

# An isolated loopback SMTP sink captures only synthetic test mail.
mailbox = queue.Queue()
class SmtpSink(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'220 test-smtp ready\r\n')
        while line := self.rfile.readline():
            command = line.upper()
            if command.startswith((b'EHLO', b'HELO')):
                self.wfile.write(b'250 localhost\r\n')
            elif command.startswith(b'DATA'):
                self.wfile.write(b'354 send data\r\n')
                message = bytearray()
                while (body := self.rfile.readline()) not in (b'.\r\n', b''):
                    message.extend(body)
                mailbox.put(message.decode('utf-8', errors='replace'))
                self.wfile.write(b'250 accepted\r\n')
            elif command.startswith(b'QUIT'):
                self.wfile.write(b'221 goodbye\r\n')
                break
            else:
                self.wfile.write(b'250 ok\r\n')

smtp = socketserver.ThreadingTCPServer(('127.0.0.1', 0), SmtpSink)
smtp.daemon_threads = True
threading.Thread(target=smtp.serve_forever, daemon=True).start()

def mail_token(purpose):
    from email.parser import Parser
    from email.policy import default
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        message = Parser(policy=default).parsestr(mailbox.get(timeout=15)).get_content()
        match = re.search(r'#password-' + purpose + r'=([A-Za-z0-9_-]{43})', message)
        if match:
            return match.group(1)
    raise AssertionError('Password mail did not arrive')

# Locate the bundled application and choose an unused loopback port for this test.
ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'backend/target/campus-tracker-2.0.0.jar'
assert JAR.exists(), 'Build the with-ui JAR before running this test.'
java_settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
java_home = re.search(r'java\.home\s*=\s*(.+)', java_settings.stderr).group(1).strip()
JAVA = str(pathlib.Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
# Reuse a cookie jar so HTTP requests share the server-issued session.
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))


# Send JSON or form data, assert the status, and decode JSON only when appropriate.
def call(path, method='GET', data=None, headers=None, expected=200, form=False, http_client=None):
    headers = dict(headers or {})
    if data is not None:
        body = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    else:
        body = None
    request = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        response = (http_client or client).open(request, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}: {raw[:300]!r}'
    return json.loads(raw) if 'application/json' in response.headers.get('Content-Type', '') and raw else raw


# Fetch a fresh token and return the header mapping needed for writes.
def csrf():
    token = call('/api/auth/csrf')
    return {token['headerName']: token['token']}


# Shut down the test JVM, escalating to a kill only if graceful termination times out.
def stop(process):
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


# Keep all database files and logs isolated from the user\'s saved applications.
with tempfile.TemporaryDirectory(prefix='campus-http-test-') as temp:
    log_path = pathlib.Path(temp) / 'server.log'
    with log_path.open('w') as log:
        command = [JAVA, '-jar', str(JAR), f'--server.port={port}', '--server.address=127.0.0.1',
                   f'--spring.datasource.url=jdbc:h2:file:{temp}/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
                   '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false']
        command += ['--campus.password.mail-enabled=true', '--campus.password.worker-delay=100',
                    f'--campus.password.public-origin={base}', '--campus.password.mail-from=campus@example.test',
                    '--campus.password.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
                    '--campus.password.rate-limit-secret=synthetic-test-secret-at-least-32-bytes',
                    '--campus.password.limits.email-minute=100',
                    '--spring.mail.host=127.0.0.1', f'--spring.mail.port={smtp.server_address[1]}',
                    '--spring.mail.properties.mail.smtp.auth=false',
                    '--spring.mail.properties.mail.smtp.starttls.enable=false',
                    '--spring.mail.properties.mail.smtp.starttls.required=false']

# Launch the JAR and wait for its health endpoint, reporting startup logs on failure.
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
# Verify the packaged UI and its assets before exercising the authenticated API.
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
# Exercise versioned create, update, soft delete, and restore through real HTTP.
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
            call('/api/auth/password-reset/request', 'POST', {'email': identity['email']}, csrf(), expected=202)
            reset_token = mail_token('reset')
        finally:
            stop(process)

        process = start()
        try:
# After restarting the JVM, verify sessions expired while stored application data survived.
            call('/api/auth/me', expected=401)
            new_password = 'Recovered-test-456!'
            call('/api/auth/password-reset/confirm', 'POST', {'token': reset_token, 'newPassword': new_password}, csrf())
            call('/api/auth/password-reset/confirm', 'POST', {'token': reset_token, 'newPassword': new_password}, csrf(), expected=400)
            call('/api/auth/login', 'POST', identity, csrf(), expected=401, form=True)
            identity['password'] = new_password
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            persisted = call(path)
            assert persisted['notes'] == 'Survives a complete JVM restart'
            assert call('/api/applications')['totalElements'] == 11
            other = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            other_csrf = call('/api/auth/csrf', http_client=other)
            other_headers = {other_csrf['headerName']: other_csrf['token']}
            call('/api/auth/login', 'POST', identity, other_headers, form=True, http_client=other)
            other_csrf = call('/api/auth/csrf', http_client=other)
            other_headers = {other_csrf['headerName']: other_csrf['token']}
            call('/api/auth/password-change/request', 'POST', {'currentPassword': identity['password']}, csrf(), expected=202)
            change_token = mail_token('change')
            change = {'token': change_token, 'newPassword': 'Changed-test-789!'}
            call('/api/auth/password-change/confirm', 'POST', change, other_headers, expected=400, http_client=other)
            call('/api/auth/password-change/confirm', 'POST', change, csrf())
            call('/api/auth/me', expected=401)
            call('/api/auth/me', expected=401, http_client=other)
            identity['password'] = change['newPassword']
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            assert call('/api/applications')['totalElements'] == 11
            call('/api/auth/logout', 'POST', headers=csrf(), expected=204)
            call('/api/applications', expected=401)
        finally:
            stop(process)

smtp.shutdown()
smtp.server_close()
print('PASS: bundled UI, CRUD, encrypted mail delivery, password recovery across restart, email-confirmed change, replay rejection, session revocation, and H2 persistence.')

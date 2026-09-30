"""Real HTTP and restart-persistence smoke test for the bundled Spring Boot JAR.

Run after npm run build and backend/mvnw -Pwith-ui package:
    python tests/http_smoke.py
Uses a temporary H2 database and test account; does not touch user data.
"""
import datetime
import atexit
import email
import http.cookiejar
import json
import os
import pathlib
import re
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# Locate the bundled application and choose an unused loopback port for this test.
ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / 'backend/target/campus-tracker-2.0.0.jar'
assert JAR.exists(), 'Build the with-ui JAR before running this test.'
# Resolve the actual JVM instead of a Windows java.exe shim that can outlive
# its launcher's PID and keep the first server bound during the restart check.
java_home = os.environ.get('JAVA_HOME')
if not java_home:
    version = subprocess.run(['java', '-XshowSettings:properties', '-version'],
                             capture_output=True, text=True, check=True)
    match = re.search(r'^\s*java\.home\s*=\s*(.+)$', version.stderr, re.MULTILINE)
    java_home = match.group(1) if match else None
java_executable = (pathlib.Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
assert java_executable and pathlib.Path(java_executable).exists(), 'Java executable not found.'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'


# Capture verification messages through a minimal loopback SMTP server.
class SmtpHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b'220 localhost Campus smoke capture\r\n')
        data_mode = False
        message = bytearray()
        while True:
            line = self.rfile.readline()
            if not line:
                return
            if data_mode:
                if line == b'.\r\n':
                    self.server.messages.append(bytes(message))
                    self.wfile.write(b'250 queued\r\n')
                    data_mode = False
                    message.clear()
                else:
                    message.extend(line[1:] if line.startswith(b'..') else line)
                continue
            command = line.decode('ascii', errors='replace').strip().upper()
            if command.startswith(('EHLO', 'HELO')):
                self.wfile.write(b'250-localhost\r\n250 SIZE 1048576\r\n')
            elif command.startswith(('MAIL FROM:', 'RCPT TO:')):
                self.wfile.write(b'250 ok\r\n')
            elif command == 'DATA':
                data_mode = True
                self.wfile.write(b'354 end with dot\r\n')
            elif command == 'QUIT':
                self.wfile.write(b'221 bye\r\n')
                return
            else:
                self.wfile.write(b'250 ok\r\n')


class SmtpCapture(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


smtp = SmtpCapture(('127.0.0.1', 0), SmtpHandler)
smtp.messages = []
threading.Thread(target=smtp.serve_forever, daemon=True).start()
atexit.register(smtp.server_close)
atexit.register(smtp.shutdown)
# Reuse a cookie jar so HTTP requests share the server-issued session.
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))


# Send JSON or form data, assert the status, and decode JSON only when appropriate.
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


# Fetch a fresh token and return the header mapping needed for writes.
def csrf():
    token = call('/api/auth/csrf')
    return {token['headerName']: token['token']}


def verification_token():
    for _ in range(100):
        for raw in smtp.messages:
            parsed = email.message_from_bytes(raw)
            body = parsed.get_payload(decode=True).decode(parsed.get_content_charset() or 'utf-8')
            match = re.search(r'#verify-email=([A-Za-z0-9_-]{43})', body)
            if match:
                return match.group(1)
        time.sleep(0.1)
    raise AssertionError('Verification message was not accepted by the SMTP capture server.')


# Shut down the test JVM, escalating to a kill only if graceful termination times out.
def stop(process):
    # Windows' `java` launcher can leave its child JVM alive after the launcher exits.
    # Stop the process tree while its PID is still available so the next JVM gets the same port.
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    elif process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    for _ in range(50):
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', port)) != 0:
                return
        time.sleep(0.1)
    raise RuntimeError(f'Test JVM still listening on port {port} after shutdown')


# Keep all database files and logs isolated from the user\'s saved applications.
with tempfile.TemporaryDirectory(prefix='campus-http-test-') as temp:
    log_path = pathlib.Path(temp) / 'server.log'
    with log_path.open('w') as log:
        command = [str(java_executable), '-jar', str(JAR), f'--server.port={port}', '--server.address=127.0.0.1',
                   f'--spring.datasource.url=jdbc:h2:file:{temp}/campus;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
                   '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false',
                   '--spring.mail.host=127.0.0.1', f'--spring.mail.port={smtp.server_address[1]}',
                   f'--campus.mail.public-origin={base}', '--campus.mail.worker-enabled=true',
                   '--campus.verification.rate-limits-enabled=false']

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
            accepted = call('/api/auth/register', 'POST', {**identity, 'displayName': 'HTTP Tester', 'includeSamples': True}, csrf(), expected=202)
            assert accepted['status'] == 'VERIFICATION_REQUEST_ACCEPTED'
            pending = call('/api/auth/login', 'POST', identity, csrf(), expected=403, form=True)
            assert pending['code'] == 'EMAIL_VERIFICATION_REQUIRED'
            token = verification_token()
            confirmed = call('/api/auth/email-verification/confirm', 'POST',
                             {'token': token, 'password': identity['password']}, csrf())
            assert confirmed['status'] == 'EMAIL_VERIFIED'
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            assert call('/api/applications')['totalElements'] == 10
            assert call('/api/me/preferences') == {'themePreference': 'system'}
            headers = csrf()
            call('/api/me/preferences', 'PUT', {'themePreference': 'dark'}, expected=403)
            assert call('/api/me/preferences', 'PUT', {'themePreference': 'dark'}, headers) == {'themePreference': 'dark'}
            call('/api/me/preferences', 'PUT', {'themePreference': 'sepia'}, headers, expected=400)
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
        finally:
            stop(process)

        process = start()
        try:
# After restarting the JVM, verify sessions expired while stored application data survived.
            call('/api/auth/me', expected=401)
            call('/api/auth/login', 'POST', identity, csrf(), form=True)
            assert call('/api/me/preferences') == {'themePreference': 'dark'}
            persisted = call(path)
            assert persisted['notes'] == 'Survives a complete JVM restart'
            assert call('/api/applications')['totalElements'] == 11
            call('/api/auth/logout', 'POST', headers=csrf(), expected=204)
            call('/api/applications', expected=401)
        finally:
            stop(process)

print('PASS: real HTTP page/assets, email verification, authentication, theme preferences, CRUD, CSRF, conflict, delete/undo, logout, and H2 restart persistence.')

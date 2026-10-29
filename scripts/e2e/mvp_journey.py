#!/usr/bin/env python3
"""Run real local services against disposable infrastructure; never load .env."""
import datetime as dt
import http.server
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ['auth', 'member', 'job', 'matching', 'payment', 'work', 'chat', 'notification']


class CommandFailure(RuntimeError):
    pass


def command(*args, timeout=30, **kwargs):
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=timeout, **kwargs)
    except subprocess.TimeoutExpired:
        raise CommandFailure(f'{args[0]} timed out after {timeout}s') from None
    if result.returncode:
        # Arguments may contain generated credentials; do not expose them in tracebacks.
        raise CommandFailure(f'{args[0]} exited with status {result.returncode}')
    return result.stdout.strip()


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def eventually(description, predicate, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.5)
    raise AssertionError('Timed out: ' + description)


class ExternalDouble(http.server.BaseHTTPRequestHandler):
    payments = {}
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b';')[0], 16)
                if not size:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            raw = b''.join(chunks)
        else:
            raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        body = json.loads(raw or b'{}')
        with self.lock:
            if self.path.startswith('/api/nts-businessman/'):
                result = {'status_code': 'OK', 'data': [{'b_no': n, 'b_stt_cd': '01'} for n in body['b_no']]}
            elif self.path == '/v1/payments/confirm':
                result = dict(body, status='DONE', currency='KRW', method='카드',
                              totalAmount=body['amount'], balanceAmount=body['amount'],
                              approvedAt=dt.datetime.now(dt.timezone.utc).isoformat())
                self.payments[body['paymentKey']] = result
            elif self.path.startswith('/v1/payments/'):
                result = self.payments.get(self.path.rsplit('/', 1)[-1])
            else:
                result = None
        payload = json.dumps(result or {'code': 'NOT_FOUND'}).encode()
        self.send_response(200 if result else 404)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(payload)


class Journey:
    def __init__(self):
        self.run_id = uuid.uuid4().hex[:12]
        self.logs = Path(tempfile.mkdtemp(prefix='wnf-journey-'))
        self.containers = []
        self.processes = []
        self.handles = []
        self.ports = {}
        for service in SERVICES:
            candidate = port()
            while candidate in self.ports.values():
                candidate = port()
            self.ports[service] = candidate
        self.secret = secrets.token_urlsafe(36)
        self.db_password = secrets.token_urlsafe(24)
        self.checks = []
        self.double = http.server.ThreadingHTTPServer(('127.0.0.1', 0), ExternalDouble)
        threading.Thread(target=self.double.serve_forever, daemon=True).start()

    def check(self, name, condition=True):
        if not condition:
            raise AssertionError(name)
        self.checks.append(name)
        print('PASS ' + name, flush=True)

    def sql(self, service, query):
        return command('docker', 'exec', '-e', 'MYSQL_PWD=' + self.db_password, self.mysql,
                       'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-N', '-B', service, '-e', query, timeout=10)

    def redis(self, *args):
        return command('docker', 'exec', self.redis_name, 'redis-cli', *map(str, args))

    def api(self, service, method, path, body=None, token=None, expected=200, internal=False, key=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if internal:
            headers['X-Internal-Secret'] = self.secret
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(f'http://127.0.0.1:{self.ports[service]}{path}',
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        status = response.code
        payload = json.loads(response.read() or b'{}')
        if status != expected:
            # Never print response bodies: auth responses can contain credentials.
            raise AssertionError(f'{service} {method} {path}: expected {expected}, got {status}; code={payload.get("code")}')
        return payload.get('data', payload)

    def start(self):
        for service in SERVICES:
            log = open(self.logs / (service + '-build.log'), 'w')
            try:
                subprocess.run(['./gradlew', 'bootJar'], cwd=ROOT / (service + '-service'),
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
            finally:
                log.close()
        self.mysql = 'wnf-journey-mysql-' + self.run_id
        self.redis_name = 'wnf-journey-redis-' + self.run_id
        self.containers.append(self.mysql)
        command('docker', 'run', '-d', '--name', self.mysql, '-p', '127.0.0.1::3306',
                '-e', 'MYSQL_ROOT_PASSWORD=' + self.db_password, '-e', 'TZ=Asia/Seoul', 'mysql:8.4')
        self.containers.append(self.redis_name)
        command('docker', 'run', '-d', '--name', self.redis_name, '-p', '127.0.0.1::6379', 'redis:7.4-alpine')
        mysql_port = command('docker', 'port', self.mysql, '3306').rsplit(':', 1)[1]
        redis_port = command('docker', 'port', self.redis_name, '6379').rsplit(':', 1)[1]
        def ready():
            try:
                self.sql('mysql', 'SELECT 1')
                return True
            except CommandFailure:
                return False
        eventually('isolated MySQL', ready, 120)
        for s in SERVICES:
            self.sql('mysql', f"CREATE DATABASE {s}; CREATE USER '{s}'@'%' IDENTIFIED BY '{self.db_password}'; GRANT ALL ON {s}.* TO '{s}'@'%';")
        env = {k: v for k, v in os.environ.items() if k in {'PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMPDIR'}}
        # All external service coordinates and credentials are overridden, independent of local .env.
        env.update(AUTH_JWT_SECRET=self.secret, INTERNAL_API_SECRET=self.secret,
                   AUTH_REDIS_HOST='127.0.0.1', AUTH_REDIS_PORT=redis_port,
                   MATCHING_REDIS_HOST='127.0.0.1', MATCHING_REDIS_PORT=redis_port,
                   NTS_BUSINESS_API_BASE_URL=f'http://127.0.0.1:{self.double.server_port}',
                   NTS_BUSINESS_API_SERVICE_KEY='local-double', TOSS_ENABLED='true',
                   TOSS_SECRET_KEY='test_sk_local_double', TOSS_API_BASE_URL=f'http://127.0.0.1:{self.double.server_port}',
                   PAYMENT_PAYOUT_ENABLED='false', WORK_ATTENDANCE_TIME_ZONE='Asia/Seoul',
                   WORK_NO_SHOW_POLL_DELAY_MS='500', WORK_OUTBOX_RETRY_BASE_DELAY='1s')
        for s in SERVICES:
            env[s.upper() + '_SERVICE_BASE_URL'] = f'http://127.0.0.1:{self.ports[s]}'
            env[s.upper() + '_SERVICE_PORT'] = str(self.ports[s])
            env[s.upper() + '_DATASOURCE_URL'] = f'jdbc:mysql://127.0.0.1:{mysql_port}/{s}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul'
            env[s.upper() + '_DATASOURCE_USERNAME'] = s
            env[s.upper() + '_DATASOURCE_PASSWORD'] = self.db_password
        # Spring properties (not .env-only names) shorten scheduler cadence in isolated processes.
        env.update(WORK_NO_SHOW_INITIAL_DELAY_MS='0', WORK_NO_SHOW_ENABLED='true')
        for s in SERVICES:
            jar = next(p for p in (ROOT / (s + '-service/build/libs')).glob('*.jar') if not p.name.endswith('-plain.jar'))
            handle = open(self.logs / (s + '.log'), 'w')
            self.handles.append(handle)
            self.processes.append(subprocess.Popen(['java', '-Xmx192m', '-jar', str(jar),
                '--work.no-show.poll-delay-ms=500', '--payment.recovery.interval=1s',
                '--payment.funding-incidents.interval=1s', '--matching.outbox.initial-delay=0s'],
                env=env, stdout=handle, stderr=subprocess.STDOUT))
        for s in SERVICES:
            def listening(service=s):
                if any(p.poll() is not None for p in self.processes):
                    raise AssertionError('A service exited; inspect ' + str(self.logs))
                try:
                    with socket.create_connection(('127.0.0.1', self.ports[service]), timeout=.2):
                        return True
                except OSError:
                    return False
            eventually(s + ' HTTP listener', listening, 180)
        self.check('eight real service HTTP listeners')

    def signup(self, role, index):
        email = f'journey{index}@example.invalid'
        phone = f'0109000{index:04d}'
        for channel, value in [('email', email), ('sms', phone)]:
            key = f'auth:verification:{channel}:verified:SIGNUP:{value}'
            if self.redis('SET', key, 'true', 'EX', 1800) != 'OK' or self.redis('GET', key) != 'true':
                raise AssertionError('Isolated verification fixture was not stored')
        body = dict(name='Journey ' + role, email=email, password='Journey-Test-123!', phoneNumber=phone, deviceId='journey')
        location = dict(address='서울특별시 중구 테스트', latitude=37.5665, longitude=126.9780)
        if role == 'owner':
            body.update(businessRegistrationNumber='123-45-67890', storeName='Journey store', businessType='CAFE', storeLocation=location)
        else:
            body.update(desiredHourlyWage=10320, activityRadiusKm=10, immediatelyAvailable=True,
                        baseLocation=location, preferredBusinessTypes=['CAFE'],
                        availableTimes=[dict(dayOfWeek='MONDAY', startTime='00:00', endTime='23:59')])
        account = self.api('auth', 'POST', '/api/auth/signup/' + role, body, expected=201)
        login = self.api('auth', 'POST', '/api/auth/login', dict(email=email, password=body['password'], deviceId='journey'))
        self.check(role + ' signup via member HTTP and real login', account['memberId'] == login['memberId'])
        return login['memberId'], login['tokenResponse']['accessToken']

    def matched(self, owner, worker, label):
        now = dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))
        start = (now + dt.timedelta(minutes=10)).replace(second=0, microsecond=0)
        end = start + dt.timedelta(hours=1)
        body = dict(businessId=owner[0], categoryId=1, storeName='Journey', address='서울특별시 중구',
                    title='Journey ' + label, description='isolated integration test', workDate=start.date().isoformat(),
                    startTime=start.strftime('%H:%M:%S'), endTime=end.strftime('%H:%M:%S'),
                    isEndTimeNextDay=end.date() != start.date(), baseHourlyWage=10320, extraWage=100,
                    recruitCount=1, latitude=37.5665, longitude=126.9780, urgencyLevel='LOW',
                    applicationDeadline=(now + dt.timedelta(minutes=5)).replace(tzinfo=None).isoformat())
        job_id = self.api('job', 'POST', '/api/jobs', body, owner[1])
        self.api('job', 'GET', f'/api/jobs/{job_id}', token=worker[1], expected=404)
        self.check(label + ' payment-pending job hidden from worker')
        eventually('payment order linked', lambda: self.sql('job', f'SELECT payment_order_id FROM job_posts WHERE id={job_id}') not in ('NULL', ''))
        order_id = self.sql('job', f'SELECT payment_order_id FROM job_posts WHERE id={job_id}')
        amount = int(self.sql('payment', f"SELECT amount FROM payment_orders WHERE id='{order_id}'").split('.')[0])
        self.api('payment', 'POST', f'/api/payments/orders/{order_id}/confirm',
                 dict(paymentKey='journey-' + str(job_id), amount=amount), owner[1])
        eventually('funding notification publishes job', lambda: self.sql('job', f'SELECT status FROM job_posts WHERE id={job_id}') == 'OPEN')
        application = self.api('matching', 'POST', '/api/applications', dict(jobPostId=job_id), worker[1])
        application_id = application['applicationId']
        matching = self.api('matching', 'POST', f'/api/jobs/{job_id}/applications/{application_id}/matchings', {}, owner[1])
        matching_id = matching['matchingId']
        self.api('matching', 'PATCH', f'/api/matchings/{matching_id}/accept', token=worker[1])
        eventually('matching confirmation saga', lambda: self.sql('matching', f'SELECT status FROM matchings WHERE id={matching_id}') == 'CONFIRMED')
        work_id = int(self.sql('work', f'SELECT id FROM works WHERE matching_id={matching_id}'))
        eventually('work confirmation event', lambda: self.sql('work', f'SELECT confirmed_at IS NOT NULL FROM works WHERE id={work_id}') == '1')
        self.check(label + ' real job/payment/matching/work/chat saga')
        return job_id, matching_id, work_id, amount, order_id

    def advance_work_schedule(self, work_id):
        # Fixture-only time travel; preserve overnight semantics near midnight.
        now = dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))
        start, end = now - dt.timedelta(minutes=5), now - dt.timedelta(minutes=1)
        self.sql('work', f"UPDATE works SET work_date='{start.date()}',start_time='{start:%H:%M:%S}',"
                 f"end_time='{end:%H:%M:%S}',end_time_next_day={int(end.date() != start.date())} WHERE id={work_id}")

    def scenarios(self):
        owner, worker, stranger = self.signup('owner', 1), self.signup('worker', 2), self.signup('worker', 3)
        j, m, w, amount, _ = self.matched(owner, worker, 'completion')
        self.api('work', 'GET', f'/api/works/me/{w}', token=stranger[1], expected=404)
        room = int(self.sql('chat', f'SELECT id FROM chat_rooms WHERE matching_id={m}'))
        eventually('chat confirmation event', lambda: self.sql('chat', f'SELECT confirmed_at IS NOT NULL FROM chat_rooms WHERE id={room}') == '1')
        message = dict(clientMessageId=uuid.uuid4().hex, content='Journey message')
        sent = self.api('chat', 'POST', f'/api/chat-rooms/me/{room}/messages', message, owner[1])
        replay = self.api('chat', 'POST', f'/api/chat-rooms/me/{room}/messages', message, owner[1])
        self.check('chat message replay is idempotent', sent == replay)
        self.api('chat', 'GET', f'/api/chat-rooms/me/{room}/messages', token=worker[1])
        self.api('chat', 'GET', f'/api/chat-rooms/me/{room}/messages', token=stranger[1], expected=404)
        self.api('work', 'POST', f'/api/works/me/{w}/start', token=owner[1], expected=409)
        # Only this disposable work fixture advances time; production policy stays unchanged.
        self.advance_work_schedule(w)
        self.api('work', 'POST', f'/api/works/me/{w}/check-in', dict(latitude=0, longitude=0), worker[1], expected=409)
        self.api('work', 'POST', f'/api/works/me/{w}/check-in', dict(latitude=37.5665, longitude=126.9780), worker[1])
        self.api('work', 'POST', f'/api/works/me/{w}/start', token=owner[1])
        self.api('work', 'POST', f'/api/works/me/{w}/complete', token=owner[1])
        eventually('completed matching', lambda: self.sql('matching', f'SELECT status FROM matchings WHERE id={m}') == 'COMPLETED')
        eventually('settlement from real WorkCompleted stream', lambda: self.sql('payment', f'SELECT COUNT(*) FROM payment_settlements WHERE work_id={w}') == '1')
        self.check('completed work reserves exact deposit amount', self.sql('payment', f'SELECT CAST(amount AS UNSIGNED) FROM payment_settlements WHERE work_id={w}') == str(amount))
        for actor in [owner, worker]:
            eventually('review entitlement', lambda: self.sql('member', f'SELECT COUNT(*) FROM reviewable_works WHERE work_id={w}') != '0')
            self.api('member', 'POST', '/api/members/me/reviews', dict(workId=w, rating=5, comment='Journey'), actor[1])
        self.check('mutual review via real event entitlement')
        settlement = int(self.sql('payment', f'SELECT id FROM payment_settlements WHERE work_id={w}'))
        for actor in [owner, worker]:
            view = self.api('payment', 'GET', f'/api/payments/me/settlements/{settlement}', token=actor[1])
            self.check('participant settlement view', int(view['amount']) == amount and view['status'] == 'RESERVED')
        self.api('payment', 'GET', f'/api/payments/me/settlements/{settlement}', token=stranger[1], expected=404)
        eventually('owner completion notification', lambda: self.sql('notification', f'SELECT COUNT(*) FROM notifications WHERE work_id={w}') != '0')
        self.api('notification', 'GET', '/api/notifications/me', token=worker[1])
        self.check('real work stream notification and authenticated inbox')
        self.check('payout stays disabled', self.sql('payment', 'SELECT COUNT(*) FROM payment_payout_commands') == '0')
        for label in ['cancellation', 'no-show', 'funding']:
            j, m, w, amount, order = self.matched(owner, worker, label)
            if label == 'cancellation':
                request = self.api('work', 'POST', f'/api/works/me/{w}/cancellations', dict(reason='Journey'), worker[1], key=uuid.uuid4().hex)
                self.api('work', 'POST', f'/api/works/me/{w}/cancellations/{request["requestId"]}/approve', token=owner[1])
                desired = 'CANCELED'
            elif label == 'no-show':
                self.sql('work', f"UPDATE works SET work_date=DATE_SUB(CURDATE(),INTERVAL 1 DAY) WHERE id={w}")
                desired = 'NO_SHOW'
            else:
                with ExternalDouble.lock:
                    ExternalDouble.payments['journey-' + str(j)]['status'] = 'CANCELED'
                    ExternalDouble.payments['journey-' + str(j)]['balanceAmount'] = 0
                self.api('payment', 'POST', '/api/payments/webhooks/toss', dict(eventType='PAYMENT_STATUS_CHANGED', data=dict(orderId=order, paymentKey='journey-' + str(j))))
                eventually('funding anomaly incident', lambda: self.sql('payment', f'SELECT COUNT(*) FROM payment_funding_incidents WHERE job_post_id={j}') == '1')
                self.check('funding anomaly preserves confirmed work', self.sql('work', f'SELECT status FROM works WHERE id={w}') == 'SCHEDULED')
                eventually('funding alerts delivered to both participants', lambda: self.sql('payment',
                    f"SELECT COUNT(*) FROM payment_funding_incident_deliveries d JOIN payment_funding_incidents i ON i.id=d.incident_id WHERE i.job_post_id={j} AND d.delivered=TRUE") == '2')
                self.check('funding incident notifications delivered through real HTTP receiver')
                self.advance_work_schedule(w)
                self.api('work', 'POST', f'/api/works/me/{w}/check-in', dict(latitude=37.5665, longitude=126.9780), worker[1])
                self.api('work', 'POST', f'/api/works/me/{w}/start', token=owner[1])
                self.api('work', 'POST', f'/api/works/me/{w}/complete', token=owner[1])
                eventually('funding-blocked completion held', lambda: self.sql('payment', f'SELECT status FROM payment_settlements WHERE work_id={w}') == 'ON_HOLD')
                self.check('funding-blocked work can complete while payout stays held')
                continue
            eventually(label + ' work terminal', lambda: self.sql('work', f'SELECT status FROM works WHERE id={w}') == desired)
            eventually(label + ' matching terminal', lambda: self.sql('matching', f'SELECT status FROM matchings WHERE id={m}') == 'CANCELED')
            eventually(label + ' deposit released', lambda: self.sql('payment', f'SELECT CAST(locked_amount AS UNSIGNED) FROM payment_deposits WHERE job_post_id={j}') == '0')
            self.check(label + ' terminal propagation and money release')

    def close(self):
        failures = []
        try:
            for process in self.processes:
                try:
                    if process.poll() is None:
                        process.terminate()
                except Exception:
                    failures.append('process termination')
            for process in self.processes:
                try:
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                except Exception:
                    failures.append('process exit')
            for handle in self.handles:
                try:
                    handle.close()
                except Exception:
                    failures.append('log handle')
        finally:
            try:
                for name in reversed(self.containers):
                    try:
                        command('docker', 'rm', '-fv', name)
                    except (CommandFailure, OSError):
                        failures.append(name)
                        print(f'WARN could not remove container {name}; manual cleanup required', flush=True)
            finally:
                try:
                    self.double.shutdown()
                finally:
                    self.double.server_close()
        if failures:
            raise CommandFailure('Cleanup incomplete: ' + ', '.join(failures))


def interrupted(_signal, _frame):
    raise KeyboardInterrupt


def main():
    signal.signal(signal.SIGTERM, interrupted)
    run = Journey()
    print('Logs: ' + str(run.logs), flush=True)
    try:
        run.start()
        run.scenarios()
        print(f'PASS {len(run.checks)} checks; real providers/browser excluded', flush=True)
    finally:
        # Cleanup is bounded; a second termination signal must not skip remaining resources.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        run.close()


if __name__ == '__main__':
    main()

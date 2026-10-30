#!/usr/bin/env python3
"""Exercise public routing/CORS against disposable upstreams, without AWS or real data."""

import base64
import pathlib
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORTS = {"auth": 8081, "member": 8082, "job": 8083, "matching": 8084,
         "payment": 8085, "work": 8086, "chat": 8087, "notification": 8088}
ORIGIN = "https://frontend.example.com"
# Throwaway Swagger account for this test only.
SWAGGER_USERNAME = "swagger-team"
SWAGGER_PASSWORD = "test-password"
SWAGGER_HASH = "$2a$14$o5CMnPkPguSqR2640kYUreZsiQsXZMMAIzIbFETGrp9dBoPbGe6DG"
SWAGGER_AUTH = "Basic " + base64.b64encode(f"{SWAGGER_USERNAME}:{SWAGGER_PASSWORD}".encode()).decode()


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.PIPE).strip()


def request(base, path, method="GET", origin=None, secret=None, authorization=None):
    headers = {}
    if authorization:
        headers["Authorization"] = authorization
    if origin:
        headers["Origin"] = origin
    if secret:
        headers["X-Internal-Secret"] = secret
    req = urllib.request.Request(base + path, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()


def verify(base):
    routes = {
        "/api/auth/login": "auth", "/api/members/me": "member",
        "/api/jobs/search": "job", "/api/jobs/1/applications": "matching",
        "/api/jobs/1/applications/2/matchings": "matching",
        "/api/jobs/1/applications-extra": "job",
        "/api/applications": "matching", "/api/matchings/1/accept": "matching",
        "/api/works/me": "work", "/api/chat-rooms/me": "chat",
        "/api/payments/webhooks/toss": "payment", "/api/notifications/me": "notification",
    }
    for path, service in routes.items():
        status, _, body = request(base, path, secret="must-not-reach-upstream")
        assert (status, body) == (200, service + "|"), (path, status, body)
    for path in ("/api/jobs/internal", "/api/jobs/internal/1", "/api/jobs/%69nternal/1",
                 "/api/payments/internal/refunds/reviews", "/api/members/internal/workers",
                 "/v3/api-docs", "/actuator/health", "/swagger-ui/index.html"):
        assert request(base, path)[0] == 404, path
    status, headers, _ = request(base, "/api/auth/login", "OPTIONS", ORIGIN)
    assert status == 204
    assert headers["Access-Control-Allow-Origin"] == ORIGIN
    assert "Authorization" in headers["Access-Control-Allow-Headers"]
    assert request(base, "/api/auth/login", "OPTIONS", "https://other.vercel.app")[0] == 403
    _, headers, _ = request(base, "/api/jobs/search", origin=ORIGIN)
    assert headers["Access-Control-Allow-Origin"] == ORIGIN
    _, headers, _ = request(base, "/api/jobs/search", origin="https://other.vercel.app")
    assert headers.get("Access-Control-Allow-Origin") is None
    verify_swagger(base)
    print("Proxy routing, internal API isolation, secret stripping, exact-Origin CORS"
          " and password-protected Swagger passed.")


def verify_swagger(base):
    for path in ("/docs/auth/swagger-ui/index.html", "/docs/auth/v3/api-docs", "/docs/unknown"):
        assert request(base, path)[0] == 401, path
    wrong = "Basic " + base64.b64encode(f"{SWAGGER_USERNAME}:wrong".encode()).decode()
    assert request(base, "/docs/auth/v3/api-docs", authorization=wrong)[0] == 401
    for service in PORTS:
        prefix = "/docs/" + service
        # The UI and its config need the prefix; the spec must not get it so its server URL stays at the API root.
        for path, upstream_path, forwarded_prefix in (
                ("/swagger-ui/index.html", "/swagger-ui/index.html", prefix),
                ("/swagger-ui.html", "/swagger-ui.html", prefix),
                ("/v3/api-docs/swagger-config", "/v3/api-docs/swagger-config", prefix),
                ("/v3/api-docs", "/v3/api-docs", "")):
            status, headers, body = request(base, prefix + path, secret="must-not-reach-upstream",
                                            authorization=SWAGGER_AUTH)
            assert (status, body) == (200, service + "|"), (prefix + path, status, body)
            assert headers["X-Seen-Path"] == upstream_path, (prefix + path, headers["X-Seen-Path"])
            assert headers["X-Seen-Prefix"] == forwarded_prefix, (prefix + path, headers["X-Seen-Prefix"])
    for path in ("/docs", "/docs/unknown/swagger-ui/index.html", "/docs/auth/actuator/health",
                 "/docs/auth/api/auth/login", "/docs/jobs/api/jobs/internal/1",
                 "/docs/job/api/jobs/internal/1", "/docs/job/v3/api-docs/internal",
                 "/docs/auth/swagger-ui", "/docs/auth/"):
        assert request(base, path, authorization=SWAGGER_AUTH)[0] == 404, path


def main():
    network = "wnf-proxy-test-" + uuid.uuid4().hex[:10]
    containers = []
    docker("network", "create", network)
    try:
        with tempfile.TemporaryDirectory(prefix="wnf-proxy-test-") as directory:
            upstream = pathlib.Path(directory) / "Caddyfile"
            upstream.write_text('{\n admin off\n}\n:{$PORT} {\n'
                                ' header X-Seen-Path {http.request.uri}\n'
                                ' header X-Seen-Prefix "{http.request.header.X-Forwarded-Prefix}"\n'
                                ' respond "{$SERVICE}|{http.request.header.X-Internal-Secret}"\n}\n')
            proxy = docker(
                "run", "-d", "--rm", "--network", network, "-p", "127.0.0.1::80",
                "-e", "API_DOMAIN=:80", "-e", "FRONTEND_ORIGIN=" + ORIGIN,
                "-e", "SWAGGER_USERNAME=" + SWAGGER_USERNAME, "-e", "SWAGGER_PASSWORD_HASH=" + SWAGGER_HASH,
                "-v", f"{ROOT / 'deploy/Caddyfile'}:/etc/caddy/Caddyfile:ro", "caddy:2-alpine")
            containers.append(proxy)
            for service, port in PORTS.items():
                containers.append(docker(
                    "run", "-d", "--rm", "--network", "container:" + proxy,
                    "-e", "SERVICE=" + service, "-e", "PORT=" + str(port),
                    "-v", f"{upstream}:/etc/caddy/Caddyfile:ro", "caddy:2-alpine"))
            address = docker("port", proxy, "80/tcp")
            base = "http://" + address
            ready_paths = ("/api/auth/login", "/api/members/me", "/api/jobs/search",
                           "/api/applications", "/api/works/me", "/api/chat-rooms/me",
                           "/api/payments/webhooks/toss", "/api/notifications/me")
            deadline = time.monotonic() + 30
            while True:
                try:
                    if all(request(base, path)[0] == 200 for path in ready_paths):
                        break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("Proxy upstreams were not ready within 30 seconds.")
                time.sleep(0.5)
            verify(base)
    finally:
        for container in reversed(containers):
            subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)
        subprocess.run(["docker", "network", "rm", network], capture_output=True, check=False)


if __name__ == "__main__":
    main()

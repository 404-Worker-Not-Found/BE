#!/usr/bin/env python3
"""Check Android deployment without a frontend domain, file precedence and invalid settings."""

import json
import os
import pathlib
import secrets
import shlex
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
# bcrypt hash of a throwaway test password, not a deployment credential.
SWAGGER_HASH = "$2a$14$o5CMnPkPguSqR2640kYUreZsiQsXZMMAIzIbFETGrp9dBoPbGe6DG"


def main():
    docker = shutil.which("docker")
    if docker is None:
        raise RuntimeError("Docker CLI with the Compose plugin is required.")
    values = {}
    for line in (ROOT / "deploy/env.example").read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            if key.endswith("PASSWORD") or key in ("AUTH_JWT_SECRET", "INTERNAL_API_SECRET"):
                value = secrets.token_hex(32)
            values[key] = value
    values.update(API_DOMAIN="api.203-0-113-10.sslip.io", IMAGE_TAG="1" * 40,
                  SWAGGER_USERNAME="swagger-team", SWAGGER_PASSWORD_HASH="'" + SWAGGER_HASH + "'")

    with tempfile.TemporaryDirectory(prefix="wnf-config-test-") as directory:
        folder = pathlib.Path(directory)
        env_file = folder / "deploy.env"
        env_file.touch(mode=0o600)

        def write_env():
            env_file.write_text("".join(f"{key}={value}\n" for key, value in values.items()))

        # Observe whether the real Compose process receives a stale, valid SHA.
        shim = folder / "docker"
        shim.write_text("#!/bin/sh\n"
                        'if [ "${IMAGE_TAG+x}" = x ]; then\n'
                        '  echo "Exported IMAGE_TAG reached Compose." >&2; exit 1\n'
                        "fi\n"
                        f"exec {shlex.quote(docker)} \"$@\"\n")
        shim.chmod(0o700)
        environment = os.environ.copy()
        environment.update(DEPLOY_ENV_FILE=str(env_file), IMAGE_TAG="2" * 40,
                           API_DOMAIN="invalid-exported-domain",
                           MYSQL_ROOT_PASSWORD="invalid-exported-password",
                           AUTH_JWT_SECRET="invalid-exported-secret")
        environment["PATH"] = str(folder) + os.pathsep + environment["PATH"]
        command = [str(ROOT / "scripts/deploy.sh"), "check"]
        write_env()
        subprocess.run(command, env=environment, check=True, capture_output=True)
        rendered = subprocess.run(
            [docker, "compose", "--env-file", str(env_file), "-f", str(ROOT / "compose.deploy.yml"),
             "config", "--format", "json"],
            env={key: value for key, value in os.environ.items() if key not in values},
            check=True, capture_output=True, text=True)
        services = json.loads(rendered.stdout)["services"]
        # Credentials belong only to notification-service and cannot be written by the app.
        credential_path = "/run/secrets/firebase-admin.json"
        for name, service in services.items():
            mounts = [mount for mount in service.get("volumes", [])
                      if mount.get("target") == credential_path]
            credential = service.get("environment", {}).get("GOOGLE_APPLICATION_CREDENTIALS")
            if name == "notification-service":
                assert credential == credential_path and len(mounts) == 1
                mount = mounts[0]
                assert mount["type"] == "bind" and mount["read_only"]
                assert mount["source"] == "/opt/wnf/secrets/firebase-admin.json"
                assert mount.get("bind", {}).get("create_host_path", False) is False
            else:
                assert credential is None and not mounts
        assert "FRONTEND_ORIGIN" not in values
        assert "FRONTEND_ORIGIN" not in services["proxy"]["environment"]
        assert services["chat-service"]["environment"]["CHAT_WEBSOCKET_ALLOWED_ORIGINS"] == (
            "https://" + values["API_DOMAIN"])
        # Browser origins must not be widened to * for native Android support.
        services["chat-service"]["environment"]["CHAT_WEBSOCKET_ALLOWED_ORIGINS"] = "*"
        invalid_config = folder / "invalid.json"
        invalid_config.touch(mode=0o600)
        invalid_config.write_text(json.dumps({"services": services}))
        invalid = subprocess.run(
            ["python3", str(ROOT / "deploy/check-config.py"), str(invalid_config)],
            capture_output=True, text=True)
        assert invalid.returncode != 0 and "CHAT_WEBSOCKET_ALLOWED_ORIGINS" in invalid.stderr
        # Caddy rejects bcrypt costs outside 4-31 at login, so check must catch them first.
        values["SWAGGER_PASSWORD_HASH"] = "'" + SWAGGER_HASH.replace("$14$", "$99$") + "'"
        write_env()
        invalid = subprocess.run(command, env=environment, capture_output=True, text=True)
        if invalid.returncode == 0 or "SWAGGER_PASSWORD_HASH" not in invalid.stderr:
            raise AssertionError("An out-of-range Swagger bcrypt cost was not rejected.")
        # Single-service recreation accepts only one application container, before touching Compose.
        for arguments in ([], ["mysql"], ["proxy"], ["auth-service member-service"],
                          ["notification-service", "auth-service"]):
            rejected = subprocess.run([str(ROOT / "scripts/deploy.sh"), "recreate", *arguments],
                                      env=environment, capture_output=True, text=True)
            if rejected.returncode == 0 or "Specify one application service" not in rejected.stderr:
                raise AssertionError(f"recreate accepted {arguments!r}.")
        values["SWAGGER_PASSWORD_HASH"] = "'" + SWAGGER_HASH + "'"
        values["IMAGE_TAG"] = "latest"
        write_env()
        invalid = subprocess.run(command, env=environment, capture_output=True, text=True)
        if invalid.returncode == 0 or "Set a full commit SHA" not in invalid.stderr:
            raise AssertionError("An invalid deployment-file image tag was not rejected.")
    print("Read-only Firebase credential isolation, single-app recreate validation, Android deployment without a frontend domain, API WebSocket origin,"
          " deployment-file precedence and invalid release tag rejection passed.")


if __name__ == "__main__":
    main()

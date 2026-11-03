#!/usr/bin/env python3
"""Check Android deployment without a frontend domain, file precedence and invalid settings."""

import json
import os
import pathlib
import secrets
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
        shim.write_text("#!/usr/bin/env python3\n" + """
import json, os, pathlib, sys
if 'IMAGE_TAG' in os.environ:
    sys.exit('Exported IMAGE_TAG reached Compose.')
args = sys.argv[1:]
if args[:1] == ['inspect']:
    if os.environ.get('FAKE_DEPLOY_INSPECT_FAIL') == 'true': sys.exit(1)
    print(os.environ['FAKE_DEPLOY_RUNNING_IMAGE'])
elif args[:2] == ['image', 'inspect']:
    if os.environ.get('FAKE_DEPLOY_IMAGE_MISSING') == 'true': sys.exit(1)
    print(os.environ['FAKE_DEPLOY_LOCAL_IMAGE_ID'])
elif args[:1] == ['compose'] and 'ps' in args:
    if os.environ.get('FAKE_DEPLOY_PS_FAIL') == 'true': sys.exit(1)
    print(os.environ['FAKE_DEPLOY_CONTAINERS'])
elif args[:1] == ['compose'] and 'up' in args:
    pathlib.Path(os.environ['FAKE_DEPLOY_UP_MARKER']).write_text(json.dumps(args))
else:
    os.execv(__DOCKER__, [__DOCKER__, *args])
""".replace('__DOCKER__', repr(docker)))
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
        write_env()
        marker = folder / "up-command.json"
        reference = services["notification-service"]["image"]
        image_id = "sha256:" + "a" * 64
        recreate_env = environment | {
            "FAKE_DEPLOY_CONTAINERS": "a" * 64,
            "FAKE_DEPLOY_RUNNING_IMAGE": "true " + reference + " " + image_id,
            "FAKE_DEPLOY_LOCAL_IMAGE_ID": image_id,
            "FAKE_DEPLOY_UP_MARKER": str(marker),
        }
        recreate = [str(ROOT / "scripts/deploy.sh"), "recreate", "notification-service"]
        rejected_cases = [
            {"FAKE_DEPLOY_CONTAINERS": ""},
            {"FAKE_DEPLOY_CONTAINERS": "aaa\nbbb"},
            {"FAKE_DEPLOY_RUNNING_IMAGE": "false " + reference + " " + image_id},
            {"FAKE_DEPLOY_RUNNING_IMAGE": "true ghcr.io/other/app:" + "2" * 40 + " " + image_id},
            {"FAKE_DEPLOY_LOCAL_IMAGE_ID": "sha256:" + "b" * 64},
            {"FAKE_DEPLOY_INSPECT_FAIL": "true"},
            {"FAKE_DEPLOY_IMAGE_MISSING": "true"},
            {"FAKE_DEPLOY_PS_FAIL": "true"},
        ]
        for overrides in rejected_cases:
            result = subprocess.run(recreate, env=recreate_env | overrides, capture_output=True, text=True)
            if result.returncode == 0 or marker.exists():
                raise AssertionError(f"Unsafe recreation was not stopped for {list(overrides)}.")
        subprocess.run(recreate, env=recreate_env, check=True, capture_output=True)
        arguments = json.loads(marker.read_text())
        assert arguments[-1] == "notification-service" and arguments.count("notification-service") == 1
        assert all(flag in arguments for flag in ("--no-deps", "--no-build", "--force-recreate", "--wait"))
        assert arguments[arguments.index("--pull") + 1] == "never"
        values["IMAGE_TAG"] = "latest"
        write_env()
        invalid = subprocess.run(command, env=environment, capture_output=True, text=True)
        if invalid.returncode == 0 or "Set a full commit SHA" not in invalid.stderr:
            raise AssertionError("An invalid deployment-file image tag was not rejected.")
    print("Read-only Firebase credential isolation, same-image recreation and fail-closed Docker checks, Android deployment without a frontend domain, API WebSocket origin,"
          " deployment-file precedence and invalid release tag rejection passed.")


if __name__ == "__main__":
    main()

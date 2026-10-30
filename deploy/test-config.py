#!/usr/bin/env python3
"""Check deployment-file precedence and rejection of an invalid release tag."""

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
        # Caddy rejects bcrypt costs outside 4-31 at login, so check must catch them first.
        values["SWAGGER_PASSWORD_HASH"] = "'" + SWAGGER_HASH.replace("$14$", "$99$") + "'"
        write_env()
        invalid = subprocess.run(command, env=environment, capture_output=True, text=True)
        if invalid.returncode == 0 or "SWAGGER_PASSWORD_HASH" not in invalid.stderr:
            raise AssertionError("An out-of-range Swagger bcrypt cost was not rejected.")
        values["SWAGGER_PASSWORD_HASH"] = "'" + SWAGGER_HASH + "'"
        values["IMAGE_TAG"] = "latest"
        write_env()
        invalid = subprocess.run(command, env=environment, capture_output=True, text=True)
        if invalid.returncode == 0 or "Set a full commit SHA" not in invalid.stderr:
            raise AssertionError("An invalid deployment-file image tag was not rejected.")
    print("Deployment-file precedence and invalid release tag rejection passed.")


if __name__ == "__main__":
    main()

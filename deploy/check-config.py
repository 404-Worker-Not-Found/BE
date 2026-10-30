#!/usr/bin/env python3
"""Validate rendered Compose settings without printing any credential values."""

import json
import ipaddress
import re
import sys
from urllib.parse import urlparse


def main():
    with open(sys.argv[1], encoding="utf-8") as config_file:
        services = json.load(config_file)["services"]
    errors = []
    credentials = services["mysql"]["environment"]
    secrets = [value for key, value in credentials.items() if key.endswith("PASSWORD")]
    auth = services["auth-service"]["environment"]
    secrets += [auth["AUTH_JWT_SECRET"], auth["INTERNAL_API_SECRET"]]
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value or "") for value in secrets):
        errors.append("Generate every database/JWT/internal secret with openssl rand -hex 32.")
    if len(set(secrets)) != len(secrets):
        errors.append("Use a different secret for every database account, JWT and internal API.")
    if any("replace-with" in value for value in secrets):
        errors.append("Replace all example secret values.")
    proxy = services["proxy"]["environment"]
    domain = proxy["API_DOMAIN"]
    label = r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    if len(domain) > 253 or not re.fullmatch(label + r"(?:\." + label + r")+", domain):
        errors.append("API_DOMAIN must be a hostname without scheme, port or path.")
    try:
        ipaddress.ip_address(domain)
        errors.append("Use a public DNS hostname for API_DOMAIN, not an IP address.")
    except ValueError:
        pass
    if domain.endswith(("example.com", "example.net", "example.org", ".invalid", ".localhost", ".test")):
        errors.append("Set a real public API_DOMAIN and point its DNS at the server.")
    origin_text = proxy["FRONTEND_ORIGIN"]
    if not re.fullmatch(r"https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?", origin_text):
        errors.append("FRONTEND_ORIGIN must be one exact HTTPS origin.")
    origin = urlparse(origin_text)
    if (origin.scheme != "https" or not origin.hostname or origin.path
            or origin.query or origin.fragment or origin.username or origin.password
            or "*" in origin.netloc):
        errors.append("FRONTEND_ORIGIN must be one exact HTTPS origin without a trailing slash.")
    if (not re.fullmatch(r"[A-Za-z0-9._-]{3,64}", proxy["SWAGGER_USERNAME"])
            or "replace-with" in proxy["SWAGGER_USERNAME"]):
        errors.append("Set SWAGGER_USERNAME to 3-64 letters, digits, dots, hyphens or underscores.")
    # Rendered JSON escapes $ as $$; the container receives single $ characters.
    swagger_hash = proxy["SWAGGER_PASSWORD_HASH"].replace("$$", "$")
    if not re.fullmatch(r"\$2[aby]\$(?:0[4-9]|[12][0-9]|3[01])\$[./A-Za-z0-9]{53}", swagger_hash):
        errors.append("Set SWAGGER_PASSWORD_HASH to a single-quoted caddy hash-password bcrypt hash.")
    for name, service in services.items():
        if name != "app-network" and service.get("ports"):
            errors.append(f"Do not publish ports for {name}.")
        if name == "app-network" and any(port["target"] not in (80, 443) for port in service.get("ports", [])):
            errors.append("Publish only HTTP/HTTPS ports from app-network.")
        if name.endswith("-service") and service["environment"].get("SERVER_ADDRESS") != "127.0.0.1":
            errors.append(f"Bind {name} only to the shared loopback address.")
        if name.endswith("-service") and not re.search(r":[0-9a-f]{40}$", service["image"]):
            errors.append(f"Set a full commit SHA IMAGE_TAG for {name}.")
    payment = services["payment-service"]["environment"]
    if str(payment["TOSS_ENABLED"]).lower() == "true" and not payment["TOSS_SECRET_KEY"].startswith("test_sk_"):
        errors.append("TOSS_ENABLED requires a Toss test secret key; live keys are unsupported.")
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Deployment configuration passed. DNS, provider credentials and memory still require runtime checks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

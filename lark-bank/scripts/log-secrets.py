#!/usr/bin/env python3
"""
No secret on any line (bank spec 0023's log-hygiene): searches log files for the secrets compose gives the services,
and for anything shaped like a credential, and names each find by file, line and what it was, never printing the
secret itself. Exits 1 if it finds any.

    scripts/log-secrets.py build/chaos-compose.json build/chaos-all.log build/chaos-load.log

The secrets are compose's: each environment value whose name says it is a key, password, token, secret or session key
(not a URL or a request's form). One of ten characters or more is searched for anywhere; a shorter one, such as the
development databases' `bank`, only where a line gives it as a value (`password=bank`, `"password":"bank"`), since
the word itself is everywhere.
"""
import json
import re
import sys

SECRET_NAME = re.compile(r"KEY|PASSWORD|TOKEN|SECRET|SESSION|PSK|CREDENTIAL")
NOT_SECRET = re.compile(r"_URL$|_FORM$|_ENDPOINT$")
LONG = 10

# What a credential looks like, whoever's it is.
SHAPES = {
    "a JWT": re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
    "a bearer token": re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/-]{16,}=*"),
    "basic credentials": re.compile(r"(?i)\bbasic\s+[A-Za-z0-9+/]{12,}={0,2}"),
}


def secrets_of(compose: dict) -> dict:
    """Each secret value, with the first place compose names it."""
    found = {}
    for service, spec in sorted(compose.get("services", {}).items()):
        for name, value in sorted((spec.get("environment") or {}).items()):
            if not value or not SECRET_NAME.search(name) or NOT_SECRET.search(name):
                continue
            for part in str(value).split(","):
                part = part.strip()
                if part:
                    found.setdefault(part, f"{service}.{name}")
    return found


def patterns_of(secrets: dict) -> list:
    out = []
    for value, where in secrets.items():
        quoted = re.escape(value)
        if len(value) >= LONG:
            out.append((where, re.compile(quoted)))
        else:
            # Given as a value: after `password=`, `"secret": "`, `key: ` and the like, and ending there.
            out.append((where, re.compile(r"(?i)(pass(word)?|secret|token|key|pwd)[\"']?\s*[:=]\s*[\"']?" + quoted + r"(?![A-Za-z0-9_-])")))
    return out


def redacted(text: str) -> str:
    return f"{text[:3]}…({len(text)} characters)"


def main(argv: list) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    with open(argv[0]) as file:
        patterns = patterns_of(secrets_of(json.load(file)))
    finds = 0
    for path in argv[1:]:
        with open(path, errors="replace") as file:
            for number, line in enumerate(file, 1):
                for where, pattern in patterns:
                    match = pattern.search(line)
                    if match:
                        finds += 1
                        print(f"{path}:{number}: the secret of {where}: {redacted(match.group(0))}")
                for shape, pattern in SHAPES.items():
                    match = pattern.search(line)
                    if match:
                        finds += 1
                        print(f"{path}:{number}: {shape}: {redacted(match.group(0))}")
    return 1 if finds else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

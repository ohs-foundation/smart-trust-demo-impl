import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from hosts import hosts_in, host_ok

ROOT = Path(__file__).resolve().parents[2]
HASH = re.compile(r"\$argon2[a-z]*\$")
PASSCODE_NAME = re.compile(r"pass[-_ ]?(?:code|hash|phrase)", re.I)
TAIFAFEKI_IMMUNIZATION = "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiImmunization"
# The summary's clinical content. The receipt Provenance keeps the holder's name ("VHL for <name>"), so a name is not IPS content.
IPS_ONLY = {"Composition", "Condition", "MedicationStatement", "MedicationRequest", "AllergyIntolerance"}


# Medplum refuses a search on Binary; its content is not a FHIR record the clients read.
UNSEARCHABLE = "Binary"
# The other Medplum projects hold the platform's own StructureDefinitions, CodeSystems and SearchParameters, which name real hosts.
OWN_PROJECT = "Taifafeki Ministry of Public Health"


def load_env():
    env = {}
    for line in (ROOT / "stack/.env").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            env[key] = value
    return env


def request(url, token=None, data=None):
    headers = {"Accept": "application/fhir+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=60) as response:
        return json.load(response)


def search(base, path, token):
    origin = urllib.parse.urlparse(base)
    url = f"{base}/{path}"
    while url:
        bundle = request(url, token)
        for entry in bundle.get("entry", []):
            if "resource" in entry:
                yield entry["resource"]
        next_url = next((link["url"] for link in bundle.get("link", []) if link.get("relation") == "next"), None)
        url = urllib.parse.urlparse(next_url)._replace(scheme=origin.scheme, netloc=origin.netloc).geturl() if next_url else None


def types_of(base, token):
    metadata = request(f"{base}/metadata?mode=full", token)
    return [item["type"] for item in metadata["rest"][0]["resource"]]


def walk(node, path=""):
    yield path, node
    if isinstance(node, dict):
        for key, value in node.items():
            yield from walk(value, f"{path}.{key}" if path else key)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, f"{path}[{index}]")


def passcode_problems(resource):
    found = []
    for path, node in walk(resource):
        if isinstance(node, str) and HASH.search(node):
            found.append(f"{path} holds a hash in the Argon2 encoded form")
        if isinstance(node, dict):
            for key in node:
                if PASSCODE_NAME.search(key):
                    found.append(f"{path}.{key} is a passcode field")
            for key in ("url", "system"):
                if isinstance(node.get(key), str) and PASSCODE_NAME.search(node[key]):
                    found.append(f"{path}.{key} names a passcode")
    return found


def ips_problems(resource):
    found = []
    for path, node in walk(resource):
        if not isinstance(node, dict):
            continue
        kind = node.get("resourceType")
        where = path or "the resource"
        if kind == "Bundle" and node.get("type") == "document":
            found.append(f"{where} is a document Bundle")
        elif kind in IPS_ONLY:
            found.append(f"{where} is a {kind}")
        elif kind == "Immunization" and TAIFAFEKI_IMMUNIZATION not in node.get("meta", {}).get("profile", []):
            found.append(f"{where} is an Immunization that is not J1's own")
        if isinstance(node.get("section"), list) and any(isinstance(s, dict) and ("entry" in s or "code" in s) for s in node["section"]):
            found.append(f"{where} carries IPS sections")
    return found


def host_problems(resource):
    return [
        f"host {host.lower()}"
        for path, node in walk(resource)
        if isinstance(node, str)
        for host in hosts_in(node, False)
        if not host_ok(host)
    ]


def main():
    env = load_env()
    stack = f"{env['STACK_HOST']}:{env['PROXY_PORT']}"
    hapi = f"http://127.0.0.1:{env['HAPI_HOST_PORT']}/fhir"
    medplum = f"http://medplum.kesaro.{stack}/fhir/R4"
    problems = []
    counts = {"HAPI": 0, "Medplum": 0}

    try:
        for kind in types_of(hapi, None):
            for resource in search(hapi, f"{kind}?_count=200", None):
                counts["HAPI"] += 1
                label = f"HAPI {kind}/{resource.get('id')}"
                problems += [f"{label}: {p}" for p in passcode_problems(resource) + host_problems(resource)]

        credentials = urllib.parse.urlencode(
            {
                "grant_type": "client_credentials",
                "client_id": env["MEDPLUM_SUPER_ADMIN_CLIENT_ID"],
                "client_secret": env["MEDPLUM_SUPER_ADMIN_CLIENT_SECRET"],
            }
        ).encode()
        token = request(f"http://medplum.kesaro.{stack}/oauth2/token", data=credentials)["access_token"]
        projects = {project["id"]: project["name"] for project in search(medplum, "Project?_count=100", token)}
        kinds = [kind for kind in types_of(medplum, token) if kind != UNSEARCHABLE]
        for project in projects:
            for kind in kinds:
                if request(f"{medplum}/{kind}?_summary=count&_project={project}", token).get("total", 0) == 0:
                    continue
                for resource in search(medplum, f"{kind}?_count=200&_project={project}", token):
                    counts["Medplum"] += 1
                    label = f"Medplum {kind}/{resource.get('id')}"
                    hosts = host_problems(resource) if projects[project] == OWN_PROJECT else []
                    problems += [f"{label}: {p}" for p in passcode_problems(resource) + ips_problems(resource) + hosts]
    except urllib.error.HTTPError as error:
        print(f"FAIL  6 data scan: {error.code} from {error.url.split('?')[0]}")
        return 1
    except (urllib.error.URLError, KeyError, OSError) as error:
        print(f"FAIL  6 data scan: the stack could not be read ({type(error).__name__}); run 'make stack-up' first")
        return 1

    if problems:
        print(f"FAIL  6 data scan: {len(problems)} problem(s) in {counts['HAPI']} HAPI and {counts['Medplum']} Medplum resources")
        for problem in problems:
            print(f"      {problem}")
        return 1
    print(
        f"OK    6 data scan: {counts['HAPI']} HAPI and {counts['Medplum']} Medplum resources hold no passcode hash or field, "
        "no IPS content (names are not read as IPS content; the receipt keeps the holder's name), no foreign host"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

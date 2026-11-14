import ipaddress
import re

# Host -> why it is not a production host the demo calls. A leading "." matches the name and any subdomain.
ALLOWED = {
    ".example": "RFC 2606 reserved name",
    ".test": "RFC 2606 reserved name",
    ".invalid": "RFC 2606 reserved name",
    ".example.org": "RFC 2606 reserved name",
    ".example.com": "RFC 2606 reserved name",
    ".sslip.io": "the demo's own hosts: STACK_HOST is <ip>.sslip.io",
    "localhost": "loopback",
    "quay.io": "container registry the stack pulls the Keycloak image from, pinned by tag",
    "services.gradle.org": "where the Gradle wrapper downloads its pinned distribution",
    "host.docker.internal": "Docker's alias for the machine running the stack",
    "smart.who.int": "canonical URL base of the IG and of WHO SMART profiles, an identifier",
    "hapifhir.io": "extension canonical HAPI adds to its own Subscription resources, an identifier",
    "ohs.dev": "canonical URL base of the Player's ViewDefinition, ViewJoinMap, ViewConfig and the demo's identifier systems, an identifier",
    ".hl7.org": "HL7 FHIR canonical URLs and terminology systems, identifiers",
    "loinc.org": "terminology system URL, an identifier",
    "snomed.info": "terminology system URL, an identifier",
    "unitsofmeasure.org": "terminology system URL, an identifier",
    "sql-on-fhir.org": "SQL-on-FHIR ViewDefinition canonical, an identifier",
    "profiles.ihe.net": "IHE profile canonical URLs (MHD, IPS), identifiers",
    "dicom.nema.org": "DICOM code system URL in the IG's AuditEvents, an identifier",
    "ehelse.no": "FHIR extension URL inside the Android FHIR questionnaires the Player template ships, an identifier never fetched",
    "w3id.org": "JSON-LD and DID context URLs, identifiers",
    "www.w3.org": "XML, DID and JSON-LD namespaces, identifiers",
    "purl.org": "Dublin Core term URLs in the DID proof options, identifiers",
    "schemas.android.com": "Android manifest XML namespaces",
    "www.apache.org": "licence URL in file headers",
    "scripts.sil.org": "SIL Open Font License URL in the bundled fonts' licence texts",
    "tng-cdn-dev.who.int": "WHO's public GDHCN DEV trust-list URL, named by the testkit's provenance and the did:web signer ids",
    "tng-cdn-uat.who.int": "WHO's public GDHCN UAT trust-list URL, a constant in libs/hcert",
    "tng-cdn.who.int": "WHO's public GDHCN production trust-list URL, a constant in libs/hcert",
    "raw.githubusercontent.com": "WHO's DEV root signer did:web id, copied from smart-trust-tools",
    "github.com": "links to upstream repositories in docs and SOURCE.yaml, never called",
    "litlfred.github.io": "documentation link to the IG's published site",
    "my-instance.zitadel.cloud": "placeholder instance name in a provider-swap test",
    "x.zitadel.cloud": "placeholder instance name in a provider-swap comment",
}

URL_HOST = re.compile(r"""(?:[A-Za-z][A-Za-z0-9+.-]*\\?:)?//(?:[^/\s"'<>@]*@)?([A-Za-z0-9.${}_-]+)""")
DID_HOST = re.compile(r"did:web:([A-Za-z0-9.-]+)")
BARE_HOST = re.compile(
    r"(?<![A-Za-z0-9_./@-])((?:[A-Za-z0-9-]+\.)+(?:com|org|net|io|dev|int|gov|edu|co|app|info|cloud|ai|no))(?![A-Za-z0-9_-])"
)


def host_ok(host: str) -> bool:
    host = host.lower()
    if host.endswith(".") or "$" in host or "{" in host:
        return True
    if "." not in host:
        return True
    try:
        address = ipaddress.ip_address(host)
        return address.is_private or address.is_loopback or address.is_unspecified
    except ValueError:
        pass
    for entry in ALLOWED:
        if entry.startswith("."):
            if host == entry[1:] or host.endswith(entry):
                return True
        elif host == entry:
            return True
    return False


def hosts_in(text: str, bare: bool):
    for pattern in (URL_HOST, DID_HOST):
        for match in pattern.finditer(text):
            yield match.group(1).rstrip(":")
    if bare:
        for match in BARE_HOST.finditer(text):
            yield match.group(1)

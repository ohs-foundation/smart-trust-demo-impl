import type { Bundle, Provenance } from "@medplum/fhirtypes";
import { describe, expect, it, vi } from "vitest";
import { ig, present, requiredPaths } from "../testing/ig";
import { buildReceipt, writeReceipt } from "./receipt";

const PROFILE = "http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiVhlReceipt";
const NOW = new Date("2026-11-14T09:30:00Z");
const bundle: Bundle = { ...ig("Bundle-TrustDemoJ4Ips.json"), identifier: { system: "urn:ietf:rfc:3986", value: "urn:uuid:8d3f1c52-6a4e-4b0f-9c1d-2e7a5b9f0c31" } };
const parts = { bundle, organizationId: "clinic-org", practitionerId: "anan-id", deviceId: "viewer-id", now: NOW };

describe("theReceiptHasTheProfilesRequiredElements", () => {
  const receipt = buildReceipt(parts);

  it("carries every element the profile requires", () => {
    for (const path of requiredPaths("StructureDefinition-TrustDemoTaifafekiVhlReceipt.json")) expect(present(receipt, path.split(".")), path).toBe(true);
    expect(receipt.meta?.profile).toEqual([PROFILE]);
    expect(receipt.target?.length).toBeGreaterThanOrEqual(1);
    expect(receipt.recorded).toBe("2026-11-14T09:30:00.000Z");
    expect(receipt.agent.length).toBeGreaterThanOrEqual(2);
    expect(receipt.entity?.length).toBeGreaterThanOrEqual(1);
  });

  it("follows the example: the viewer, Anan and the holder; the link and the Consent", () => {
    const example: Provenance = ig("Provenance-TrustDemoJ41Received.json");
    expect(receipt.activity).toEqual(example.activity);
    expect(receipt.agent[0]?.who).toEqual({ reference: "Device/viewer-id" });
    expect(receipt.agent[1]?.who).toEqual({ reference: "Practitioner/anan-id" });
    expect(receipt.agent[2]?.type).toEqual({ text: "Holder: showed the VHL QR and said the passcode" });
    expect(receipt.agent[2]?.who.reference).toBeUndefined();
    expect(receipt.entity?.map((entity) => entity.role)).toEqual(["source", "source"]);
    expect(receipt.entity?.[0]?.what.display).toBe("VHL for Somsak SENGMANY");
    expect(receipt.entity?.[1]?.what.reference).toBe("http://smart.who.int/trust-demo/Consent/TrustDemoJ4Consent");
  });

  it("names the IPS Bundle by its identifier and sits in the clinic's compartment", () => {
    expect(receipt.target).toEqual([{ type: "Bundle", identifier: bundle.identifier }]);
    expect(receipt.meta?.accounts).toEqual([{ reference: "Organization/clinic-org" }]);
  });

  it("holds none of the summary's clinical content", () => {
    const text = JSON.stringify(receipt);
    for (const content of ["XXR-4821", "Hepatitis", "2025-10-28", "BCG"]) expect(text).not.toContain(content);
  });
});

describe("writeReceipt", () => {
  it("looks the viewer device up by its IG id and creates one Provenance", async () => {
    const searchOne = vi.fn(async () => ({ resourceType: "Device" as const, id: "viewer-id" }));
    const createResource = vi.fn(async (resource: Provenance) => resource);
    await writeReceipt({ searchOne, createResource } as never, { bundle, organizationId: "clinic-org", practitionerId: "anan-id", now: NOW });
    expect(searchOne).toHaveBeenCalledWith("Device", { identifier: "http://ohs.dev/fhir/trust-demo/ig-id|TrustDemoClinicViewer" });
    expect(createResource).toHaveBeenCalledTimes(1);
    expect(createResource.mock.calls[0]?.[0].agent[0]?.who).toEqual({ reference: "Device/viewer-id" });
  });

  it("writes nothing when the viewer device is not set up", async () => {
    const createResource = vi.fn();
    const write = writeReceipt({ searchOne: async () => undefined, createResource } as never, { bundle, organizationId: "o", practitionerId: "p", now: NOW });
    await expect(write).rejects.toThrow("viewer device");
    expect(createResource).not.toHaveBeenCalled();
  });
});

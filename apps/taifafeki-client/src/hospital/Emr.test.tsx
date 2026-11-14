// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { searchAnansWorkplaces } from "../testing/workplaces";
import { Emr, type EmrSearch } from "./Emr";

vi.mock("@tanstack/react-router", () => ({ Link: ({ children }: { children: ReactNode }) => <a href="/">{children}</a> }));

const PRODUCTS = [
  { code: "vp-hepb-peds-monovalent", display: "Hep B" },
  { code: "vp-bcg-freeze-dried", display: "BCG" },
];

function mount(organization: string) {
  const executeBatch = vi.fn(async () => ({ resourceType: "Bundle", type: "transaction-response", entry: [{ response: { location: "Patient/new/_history/1" } }] }));
  const readResource = vi.fn(async (type: string, id: string) => ({ resourceType: type, id }));
  const searchResources = vi.fn(async (type: string) => (type === "Patient" ? [] : searchAnansWorkplaces(type)));
  const medplum = {
    isLoading: () => false,
    getProfile: () => ({ resourceType: "Practitioner", id: "anan-id" }),
    getAccessToken: () => "anan-token",
    searchResources,
    searchOne: async (type: string) => (type === "Location" ? { resourceType: "Location", id: "loc-1" } : undefined),
    readResource,
    valueSetExpand: async () => ({ expansion: { contains: PRODUCTS } }),
    executeBatch,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  } as unknown as MedplumClient;
  const search: EmrSearch = { organization, patient: "", screen: "emr", discharged: "" };
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MedplumProvider medplum={medplum}>
        <Emr search={search} go={() => undefined} />
      </MedplumProvider>
    </QueryClientProvider>,
  );
  return { executeBatch, readResource, searchResources };
}

afterEach(cleanup);

describe("anEmrRecordLandsInTheHospitalOnly", () => {
  it("registers into the hospital's compartment", async () => {
    const found = mount("hospital-org");
    await waitFor(() => expect(found.readResource).toHaveBeenCalledWith("Organization", "hospital-org"));
    await screen.findByText("Newborn");
    fireEvent.change(screen.getByLabelText(/Given name/), { target: { value: "Somsak" } });
    fireEvent.change(screen.getByLabelText(/Family name/), { target: { value: "Sengmany" } });
    fireEvent.change(screen.getByLabelText(/Hospital number/), { target: { value: "CRH-HN-68-104377" } });
    await waitFor(() => expect(screen.getAllByLabelText(/Lot/)).toHaveLength(2));
    for (const lot of screen.getAllByLabelText(/Lot/)) fireEvent.change(lot, { target: { value: "XXR-4821" } });
    fireEvent.click(screen.getByRole("button", { name: "Save Doses & Open Discharge" }));
    await waitFor(() => expect(found.executeBatch).toHaveBeenCalledTimes(1));
    const calls = found.executeBatch.mock.calls as unknown as [{ entry: { resource: { meta: { accounts: unknown } } }[] }][];
    for (const entry of calls[0]?.[0].entry ?? []) expect(entry.resource.meta.accounts).toEqual([{ reference: "Organization/hospital-org" }]);
  });
});

describe("aUrlNamingAnotherOrganisationWritesNothing", () => {
  it.each([["the clinic", "clinic-org"], ["nobody", ""], ["an unknown organisation", "elsewhere"]])("shows the workplace picker for a URL naming %s", async (_, organization) => {
    const found = mount(organization);
    expect(await screen.findByRole("heading", { name: "Where are you working?" })).toBeTruthy();
    expect(screen.queryByText("Loading")).toBeNull();
    expect(found.readResource).not.toHaveBeenCalled();
    expect(found.executeBatch).not.toHaveBeenCalled();
  });
});

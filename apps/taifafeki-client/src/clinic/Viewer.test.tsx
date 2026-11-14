// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { WitVhl } from "smart-trust-kotlin-hcert-core";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchSummary, scanVhl, trustListStatuses } from "../server/links";
import { ig } from "../testing/ig";
import { searchAnansWorkplaces } from "../testing/workplaces";
import { Viewer } from "./Viewer";

vi.mock("@tanstack/react-router", () => ({ Link: ({ children }: { children: ReactNode }) => <a href="/">{children}</a> }));
vi.mock("../server/links", () => ({ scanVhl: vi.fn(), fetchSummary: vi.fn(), trustListStatuses: vi.fn() }));

const IPS = ig("Bundle-TrustDemoJ4Ips.json");
const vhl = WitVhl.getInstance();
const KEY = vhl.newKey();
const JWE = vhl.encrypt(Buffer.from(JSON.stringify(IPS)), "application/fhir+json", KEY);
const MARKERS = ["SENGMANY", "XXR-4821", "Hepatitis", KEY, JWE.split(".")[3] ?? ""];
const indexedDbOpen = vi.fn();

function medplumWith(createResource: (resource: unknown) => Promise<unknown>) {
  return {
    isLoading: () => false,
    getProfile: () => ({ resourceType: "Practitioner", id: "anan-id" }),
    getAccessToken: () => "anan-token",
    searchResources: searchAnansWorkplaces,
    searchOne: async () => ({ resourceType: "Device", id: "viewer-id" }),
    createResource,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  } as unknown as MedplumClient;
}

function mount(createResource = vi.fn(async (resource: unknown) => resource), organization = "clinic-org") {
  const queries = new QueryClient();
  render(
    <QueryClientProvider client={queries}>
      <MedplumProvider medplum={medplumWith(createResource)}>
        <Viewer organization={organization} />
      </MedplumProvider>
    </QueryClientProvider>,
  );
  return { queries, createResource };
}

async function openSummary() {
  fireEvent.change(await screen.findByLabelText(/paste the link text/i), { target: { value: "vhlink:/abc" } });
  fireEvent.click(screen.getByRole("button", { name: "Check link" }));
  fireEvent.change(await screen.findByLabelText("Passcode"), { target: { value: "0482" } });
  fireEvent.click(screen.getByRole("button", { name: "Open Records" }));
}

beforeEach(() => {
  vi.mocked(trustListStatuses).mockResolvedValue([]);
  vi.mocked(scanVhl).mockResolvedValue({ kind: "fetch", url: "https://vhl.example/x", label: "Health summary", exp: 4_102_444_800, needsPasscode: true, handle: "h1" });
  vi.mocked(fetchSummary).mockResolvedValue({ kind: "released", documents: [{ contentType: "application/jose", jwe: JWE }], key: KEY });
  vi.spyOn(Storage.prototype, "setItem");
  vi.stubGlobal("indexedDB", { open: indexedDbOpen });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  indexedDbOpen.mockClear();
});

describe("nothingOfTheSummaryReachesStorage", () => {
  it("leaves storage and the query cache empty of it, while open and after Close Summary", async () => {
    const { queries } = mount();
    await openSummary();
    expect(await screen.findByText(/Somsak SENGMANY/)).toBeTruthy();

    const held = () =>
      JSON.stringify([
        queries.getQueryCache().getAll().map((query) => query.state.data),
        queries.getMutationCache().getAll().map((mutation) => [mutation.state.data, mutation.state.variables]),
      ]);
    const stored = () => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    const check = () => {
      expect(Storage.prototype.setItem).not.toHaveBeenCalled();
      expect(indexedDbOpen).not.toHaveBeenCalled();
      for (const marker of MARKERS) {
        expect(held()).not.toContain(marker);
        expect(stored()).not.toContain(marker);
      }
    };
    check();

    fireEvent.click(screen.getByRole("button", { name: "Close Summary" }));
    await waitFor(() => expect(screen.queryByText(/Somsak SENGMANY/)).toBeNull());
    expect(document.body.innerHTML).not.toContain("SENGMANY");
    check();
  });
});

describe("aReceiptLandsInTheClinicOnly", () => {
  it("writes the receipt into the clinic's compartment", async () => {
    const { createResource } = mount();
    await openSummary();
    await waitFor(() => expect(createResource).toHaveBeenCalledTimes(1));
    expect(createResource).toHaveBeenCalledWith(expect.objectContaining({ meta: expect.objectContaining({ accounts: [{ reference: "Organization/clinic-org" }] }) }));
  });

  it.each([["the hospital", "hospital-org"], ["nobody", ""]])("shows the workplace picker for a URL naming %s", async (_, organization) => {
    const { createResource } = mount(undefined, organization);
    expect(await screen.findByRole("heading", { name: "Where are you working?" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Check link" })).toBeNull();
    expect(createResource).not.toHaveBeenCalled();
  });
});

describe("theSummaryScreen", () => {
  it("shows the patient, the doses and the source, then returns to the viewer on Close Summary", async () => {
    const { createResource } = mount();
    await openSummary();
    await screen.findByText(/Somsak SENGMANY/);
    for (const text of [/2025-10-28/, /male/, /Practitioner\/TrustDemoAnan/, /Onesa national health portal/]) expect(screen.getAllByText(text).length).toBeGreaterThan(0);
    await waitFor(() => expect(screen.getByText(/receipt recorded/i)).toBeTruthy());
    expect(createResource).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Close Summary" }));
    expect(await screen.findByRole("button", { name: "Check link" })).toBeTruthy();
  });

  it("shows a plain notice and no receipt when the summary does not open", async () => {
    vi.mocked(fetchSummary).mockResolvedValue({ kind: "released", documents: [{ contentType: "application/jose", jwe: JWE }], key: vhl.newKey() });
    const { createResource } = mount();
    await openSummary();
    expect((await screen.findByRole("alert")).textContent).toBe("The summary could not be opened");
    expect(document.body.innerHTML).not.toContain(KEY);
    expect(createResource).not.toHaveBeenCalled();
  });

  it("shows Onesa's refusal and keeps the link for another try", async () => {
    vi.mocked(fetchSummary).mockResolvedValue({ kind: "refused", message: "Wrong passcode." });
    mount();
    await openSummary();
    expect((await screen.findByRole("alert")).textContent).toBe("Wrong passcode.");
    expect(screen.getByRole("button", { name: "Open Records" })).toBeTruthy();
  });

  it("keeps the summary open and says so when the receipt cannot be written", async () => {
    mount(vi.fn(async () => Promise.reject(new Error("denied"))));
    await openSummary();
    await screen.findByText(/Somsak SENGMANY/);
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("receipt could not be recorded"));
    expect(screen.getByText(/Somsak SENGMANY/)).toBeTruthy();
  });
});

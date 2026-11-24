// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Immunization, Patient } from "@medplum/fhirtypes";
import { readCard } from "../server/readCard";
import { BCG, HEPB } from "../testing/cards";
import { readCards, readChart } from "./api";
import { HOSPITAL_NUMBER_SYSTEM } from "./records";
import { PrintStation } from "./PrintStation";

vi.mock("./api", async (original) => {
  const api = await original<typeof import("./api")>();
  return { ...api, readChart: vi.fn(api.readChart), readCards: vi.fn(api.readCards) };
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("aBlankHospitalNumberNeverSearches", () => {
  it("returns no cards and does not search", async () => {
    const searchResources = vi.fn(async () => []);
    const read = vi.fn(async () => []);
    const issue = vi.fn(async () => "HC1:X");
    const medplum = { searchResources } as unknown as Pick<MedplumClient, "searchResources" | "createResourceIfNoneExist">;
    for (const value of ["", "   "]) {
      const patient = { resourceType: "Patient", identifier: [{ system: HOSPITAL_NUMBER_SYSTEM, value }] } as Patient;
      expect(await readCards(medplum, patient, [{ resourceType: "Immunization", id: "d", status: "completed" } as Immunization], "t", { issue, read })).toEqual([]);
    }
    expect(searchResources).not.toHaveBeenCalled();
    expect(issue).not.toHaveBeenCalled();
  });

  it("tells the print station's user plainly", async () => {
    const searchResources = vi.fn(async () => []);
    const medplum = {
      getAccessToken: () => "t",
      isLoading: () => false,
      getProfile: () => ({ resourceType: "Practitioner", id: "anan" }),
      readResource: async () => ({ resourceType: "Patient", id: "p1" }),
      searchResources,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    } as unknown as MedplumClient;
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MedplumProvider medplum={medplum}>
          <PrintStation header={() => null} patientId="p1" onBack={() => undefined} />
        </MedplumProvider>
      </QueryClientProvider>,
    );
    expect((await screen.findByRole("alert")).textContent).toBe("This patient has no hospital number, so no cards can be looked up.");
    expect(searchResources).not.toHaveBeenCalledWith("DocumentReference", expect.anything());
  });
});

describe("theIgsPrintStationStates", () => {
  const cards = [readCard("somsak-hepb", HEPB), readCard("somsak-bcg", BCG)];

  function mount() {
    vi.mocked(readChart).mockResolvedValueOnce({ patient: { resourceType: "Patient", id: "p1" } as Patient, doses: [] });
    vi.mocked(readCards).mockResolvedValueOnce(cards);
    vi.spyOn(window, "print").mockImplementation(() => undefined);
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MedplumProvider medplum={{ getAccessToken: () => "t", isLoading: () => false, getProfile: () => ({ resourceType: "Practitioner", id: "anan" }), addEventListener: () => undefined, removeEventListener: () => undefined } as unknown as MedplumClient}>
          <PrintStation header={() => null} patientId="p1" onBack={() => undefined} />
        </MedplumProvider>
      </QueryClientProvider>,
    );
  }

  it("inspects each card's product code, lot, date and key id", async () => {
    mount();
    fireEvent.click(await screen.findByRole("button", { name: "Inspect Raw DVCMinPreQual & CBOR" }));
    const [first] = screen.getAllByRole("heading", { name: /^Card 1 of 2/ });
    const panel = first?.closest("[data-slot=card]") as HTMLElement;
    expect(within(panel).getByText("a3K3X000006MiiXUAS")).toBeTruthy();
    expect(within(panel).getByText("XXR-4821")).toBeTruthy();
    expect(within(panel).getByText(cards[0]?.date ?? "")).toBeTruthy();
    expect(within(panel).getByText(cards[0]?.kid ?? "")).toBeTruthy();
    expect(cards[0]?.kid).toMatch(/^[0-9a-f]{16}$/);
    expect(screen.getAllByRole("heading", { name: /^Card 2 of 2/ })).toHaveLength(1);
  });

  it("confirms the cards printed, and still prints again", async () => {
    mount();
    fireEvent.click(await screen.findByRole("button", { name: "Print 2 Cards" }));
    expect(window.print).toHaveBeenCalledTimes(1);
    fireEvent(window, new Event("afterprint"));
    const done = await screen.findByRole("button", { name: "✓ 2 DVC Cards Printed" });
    fireEvent.click(done);
    expect(window.print).toHaveBeenCalledTimes(2);
  });
});

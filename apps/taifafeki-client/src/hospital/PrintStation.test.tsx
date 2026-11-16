// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Immunization, Patient } from "@medplum/fhirtypes";
import { readCards } from "./api";
import { HOSPITAL_NUMBER_SYSTEM } from "./records";
import { PrintStation } from "./PrintStation";

afterEach(cleanup);

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

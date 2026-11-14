// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { organization, role } from "./testing/workplaces";
import { WorkplaceHome } from "./Workplaces";

const ORGANIZATIONS = [organization("hospital-org"), organization("clinic-org")];

async function mount(roles = [role("hospital-org", "facility-emr"), role("clinic-org", "vhl-viewer")], choose = false) {
  const medplum = {
    isLoading: () => false,
    getProfile: () => ({ resourceType: "Practitioner", id: "anan-id" }),
    searchResources: async (type: string) => (type === "PractitionerRole" ? roles : ORGANIZATIONS),
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  } as unknown as MedplumClient;
  const root = createRootRoute();
  const home = createRoute({ getParentRoute: () => root, path: "/", component: () => <WorkplaceHome choose={choose} /> });
  const hospital = createRoute({ getParentRoute: () => root, path: "/hospital", component: () => <p>Hospital opened</p> });
  const clinic = createRoute({ getParentRoute: () => root, path: "/clinic", component: () => <p>Clinic opened</p> });
  const router = createRouter({ routeTree: root.addChildren([home, hospital, clinic]), history: createMemoryHistory({ initialEntries: ["/"] }) });
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MedplumProvider medplum={medplum}>
        <RouterProvider router={router} />
      </MedplumProvider>
    </QueryClientProvider>,
  );
  return router;
}

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe("oneWorkplaceOpensStraightIn", () => {
  it("opens the only workplace without a picker", async () => {
    const router = await mount([role("clinic-org", "vhl-viewer")]);
    expect(await screen.findByText("Clinic opened")).toBeTruthy();
    expect(router.state.location.search).toEqual({ organization: "clinic-org" });
    expect(screen.queryByRole("heading", { name: "Where are you working?" })).toBeNull();
  });
});

describe("theLastUsedWorkplaceOpens", () => {
  it("opens the remembered workplace of two", async () => {
    localStorage.setItem("taifafeki.workplace.anan-id", JSON.stringify({ organization: "clinic-org", configuration: "clinic" }));
    await mount();
    expect(await screen.findByText("Clinic opened")).toBeTruthy();
  });

  it.each([["nothing is remembered", undefined], ["the remembered one is gone", { organization: "gone-org", configuration: "clinic" }], ["it is another profile's", "other"]])("shows the picker when %s", async (_, remembered) => {
    if (remembered === "other") localStorage.setItem("taifafeki.workplace.someone-else", JSON.stringify({ organization: "clinic-org", configuration: "clinic" }));
    else if (remembered) localStorage.setItem("taifafeki.workplace.anan-id", JSON.stringify(remembered));
    await mount();
    expect(await screen.findByRole("heading", { name: "Where are you working?" })).toBeTruthy();
  });

  it("shows the picker when asked to change workplace", async () => {
    localStorage.setItem("taifafeki.workplace.anan-id", JSON.stringify({ organization: "clinic-org", configuration: "clinic" }));
    await mount(undefined, true);
    expect(await screen.findByRole("heading", { name: "Where are you working?" })).toBeTruthy();
  });
});

describe("noWorkplaceSaysSo", () => {
  it("says no workplace is assigned, with no picker", async () => {
    await mount([role("hospital-org", "chw")]);
    expect(await screen.findByText("No workplace is assigned to you.")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Where are you working?" })).toBeNull();
  });
});

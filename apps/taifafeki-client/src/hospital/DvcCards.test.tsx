// @vitest-environment jsdom
import type { MedplumClient } from "@medplum/core";
import type { DocumentReference, Immunization, Patient } from "@medplum/fhirtypes";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import jsQR from "jsqr";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readCard } from "../server/readCard";
import { BCG, HEPB } from "../testing/cards";
import { searchAnansWorkplaces } from "../testing/workplaces";
import { DvcCards } from "./DvcCards";
import { Emr } from "./Emr";
import { HOSPITAL_NUMBER_SYSTEM, PRODUCT_SYSTEM } from "./records";

vi.mock("@tanstack/react-router", () => ({ Link: ({ children }: { children: ReactNode }) => <a href="/">{children}</a> }));

const issueCard = vi.fn(async ({ data }: { data: { immunizationId: string } }) => (data.immunizationId === "d-hepb" ? HEPB : BCG));
vi.mock("../server/issue", () => ({ issueCard: (request: { data: { immunizationId: string } }) => issueCard(request) }));
vi.mock("../server/cards", () => ({
  readCardTexts: async ({ data }: { data: { texts: { doseId: string; hc1: string }[] } }) => data.texts.map((text) => readCard(text.doseId, text.hc1)),
}));

const patient = { resourceType: "Patient", id: "p1", identifier: [{ system: HOSPITAL_NUMBER_SYSTEM, value: "HN-1" }] } as Patient;
const doses = [dose("d-hepb", "a3K3X000006MiiXUAS", "Hep B vaccine"), dose("d-bcg", "a3K3X000006MiisUAC", "BCG vaccine")];

function dose(id: string, code: string, display: string): Immunization {
  return { resourceType: "Immunization", id, status: "completed", vaccineCode: { coding: [{ system: PRODUCT_SYSTEM, code, display }] } } as Immunization;
}

function stored(id: string, doseId: string, hc1: string, status: "current" | "superseded" = "current"): DocumentReference {
  return { resourceType: "DocumentReference", id, status, content: [{ attachment: { title: `${doseId}.hc1`, data: btoa(hc1) } }] } as DocumentReference;
}

function fakeMedplum(records: DocumentReference[]) {
  const search = vi.fn(async (type: string, query?: Record<string, string>) =>
    type === "DocumentReference" ? records.filter((it) => !query?.status || it.status === query.status) : [],
  );
  const create = vi.fn(async (record: DocumentReference) => {
    records.push({ ...record, id: `new-${records.length}` });
    return record;
  });
  const update = vi.fn(async (record: DocumentReference) => {
    records.splice(records.findIndex((it) => it.id === record.id), 1, record);
    return record;
  });
  const medplum = {
    getAccessToken: () => "anan-token",
    getProfile: () => ({ resourceType: "Practitioner", id: "anan-id" }),
    isLoading: () => false,
    searchResources: search,
    createResourceIfNoneExist: create,
    updateResource: update,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  } as unknown as MedplumClient;
  return { medplum, records, create, update };
}

function mount(records: DocumentReference[]) {
  const found = fakeMedplum(records);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MedplumProvider medplum={found.medplum}>
        <DvcCards patient={patient} doses={doses} />
      </MedplumProvider>
    </QueryClientProvider>,
  );
  return found;
}

beforeEach(() => {
  issueCard.mockClear();
});
afterEach(cleanup);

describe("aPatientsStoredCardsAreListed", () => {
  const records = () => [stored("r1", "d-hepb", HEPB), stored("r2", "d-bcg", BCG)];

  it("lists each dose's product, lot and date, and shows a QR", async () => {
    mount(records());
    const hepb = readCard("d-hepb", HEPB);
    const row = (await screen.findByText("Hep B vaccine")).closest("li") as HTMLElement;
    expect(within(row).getByText(`Lot ${hepb.lot}`)).toBeTruthy();
    expect(within(row).getByText(`Given ${hepb.date}`)).toBeTruthy();
    expect(screen.getByText("BCG vaccine")).toBeTruthy();
    await waitFor(() => expect(screen.getAllByRole("img", { hidden: true })).toHaveLength(2));
    fireEvent.click(within(row).getByRole("button", { name: "Show QR" }));
    await waitFor(() => expect(screen.getAllByRole("img", { name: `Vaccination card QR for ${hepb.product}`, hidden: true })).toHaveLength(2));
    expect(screen.getByText(`Card 1 of 2 · Hep B vaccine · lot ${hepb.lot}`)).toBeTruthy();
    expect(issueCard).not.toHaveBeenCalled();
  });

  it("prints every card and inspects the raw payloads", async () => {
    vi.spyOn(window, "print").mockImplementation(() => undefined);
    mount(records());
    fireEvent.click(await screen.findByRole("button", { name: "Print cards" }));
    expect(window.print).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getAllByRole("img", { hidden: true })).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Inspect Raw DVCMinPreQual & CBOR" }));
    expect(screen.getAllByRole("heading", { name: /^Card \d of 2 • DVCMinPreQual CBOR/ })).toHaveLength(2);
  });
});

function decode(svg: Element): string | undefined {
  const [, , width = 0] = (svg.getAttribute("viewBox") ?? "").split(" ").map(Number);
  const scale = 8;
  const side = width * scale;
  const pixels = new Uint8ClampedArray(side * side * 4).fill(255);
  for (const [, x, y] of (svg.querySelector("path")?.getAttribute("d") ?? "").matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    for (let dy = 0; dy < scale; dy++) {
      for (let dx = 0; dx < scale; dx++) {
        const at = ((Number(y) * scale + dy) * side + Number(x) * scale + dx) * 4;
        pixels.fill(0, at, at + 3);
      }
    }
  }
  return jsQR(pixels, side, side)?.data;
}

describe("theQrIsScannable", () => {
  it("is a square on white with a quiet zone of 4 modules, and decodes to the card", async () => {
    mount([stored("r1", "d-hepb", HEPB), stored("r2", "d-bcg", BCG)]);
    const row = (await screen.findByText("Hep B vaccine")).closest("li") as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Show QR" }));
    const svg = (await screen.findByRole("figure")).querySelector("svg") as SVGSVGElement;
    const [x, y, width, height] = (svg.getAttribute("viewBox") ?? "").split(" ").map(Number);
    expect([x, y]).toEqual([0, 0]);
    expect(width).toBe(height);
    expect(svg.querySelector("rect")?.getAttribute("fill")).toBe("#fff");
    const cells = Array.from(svg.querySelector("path")?.getAttribute("d")?.matchAll(/M(\d+) (\d+)h/g) ?? []).map((it) => [Number(it[1]), Number(it[2])]);
    expect(Math.min(...cells.flat())).toBe(4);
    expect(Math.max(...cells.flat())).toBe((width ?? 0) - 5);
    expect(svg.classList.contains("min-w-70")).toBe(true);
    expect(decode(svg)).toBe(HEPB);
  });

  it("goes full screen, cycles between the cards and closes on Close and on Esc", async () => {
    mount([stored("r1", "d-hepb", HEPB), stored("r2", "d-bcg", BCG)]);
    const row = (await screen.findByText("Hep B vaccine")).closest("li") as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Show QR" }));
    fireEvent.click(await screen.findByRole("button", { name: "Full screen" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog.className).toMatch(/fixed inset-0.*items-center justify-center/);
    expect(decode(dialog.querySelector("svg") as Element)).toBe(HEPB);
    fireEvent.click(within(dialog).getByRole("button", { name: "Next" }));
    expect(decode(dialog.querySelector("svg") as Element)).toBe(BCG);
    expect(within(dialog).getByText(/^Card 2 of 2 · BCG vaccine/)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Previous" }));
    expect(decode(dialog.querySelector("svg") as Element)).toBe(HEPB);
    fireEvent.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Full screen" }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("hides Previous and Next for a single card", async () => {
    mount([stored("r1", "d-hepb", HEPB)]);
    fireEvent.click(await screen.findByRole("button", { name: "Show QR" }));
    fireEvent.click(await screen.findByRole("button", { name: "Full screen" }));
    expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
    expect(screen.getByRole("button", { name: "Close" })).toBeTruthy();
  });
});

describe("doseWithoutACardGetsIssued", () => {
  it("calls the issuer per dose, stores each card and lists them", async () => {
    const found = mount([]);
    fireEvent.click(await screen.findByRole("button", { name: "Issue DVC cards" }));
    await screen.findByRole("button", { name: "Print cards" });
    expect(issueCard.mock.calls.map(([request]) => request.data.immunizationId)).toEqual(["d-hepb", "d-bcg"]);
    expect(found.create).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Hep B vaccine")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Issue DVC cards" })).toBeNull();
  });
});

describe("reissueKeepsTheEarlierCardSuperseded", () => {
  it("asks first, then signs a new card and supersedes the old one", async () => {
    const found = mount([stored("r1", "d-hepb", HEPB), stored("r2", "d-bcg", BCG)]);
    const row = (await screen.findByText("Hep B vaccine")).closest("li") as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Re-issue card" }));
    expect(issueCard).not.toHaveBeenCalled();
    fireEvent.click(within(row).getByRole("button", { name: "Confirm re-issue" }));
    await waitFor(() => expect(screen.getByText("Superseded")).toBeTruthy());
    expect(issueCard).toHaveBeenCalledTimes(1);
    const [record] = found.create.mock.calls[0] ?? [];
    expect(record?.relatesTo).toEqual([{ code: "replaces", target: { reference: "DocumentReference/r1" } }]);
    expect(found.update).toHaveBeenCalledWith(expect.objectContaining({ id: "r1", status: "superseded" }));
    expect(found.records.find((it) => it.id === "r1")?.status).toBe("superseded");
    const rows = screen.getAllByRole("listitem");
    expect(rows[0]?.textContent).toContain("Hep B vaccine");
    expect(within(rows[0] as HTMLElement).getByRole("button", { name: "Re-issue card" })).toBeTruthy();
    expect(within(rows[1] as HTMLElement).getByText("Superseded")).toBeTruthy();
  });
});

describe("theViewerWorkplaceCannotReachTheCards", () => {
  it("shows the workplace picker and reads nothing for the clinic", async () => {
    const search = vi.fn(async (type: string) => searchAnansWorkplaces(type));
    const readResource = vi.fn();
    const medplum = {
      isLoading: () => false,
      getProfile: () => ({ resourceType: "Practitioner", id: "anan-id" }),
      getAccessToken: () => "anan-token",
      searchResources: search,
      readResource,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    } as unknown as MedplumClient;
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MedplumProvider medplum={medplum}>
          <Emr search={{ organization: "clinic-org", patient: "p1", screen: "emr", discharged: "" }} go={() => undefined} />
        </MedplumProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("heading", { name: "Where are you working?" })).toBeTruthy();
    expect(screen.queryByText("DVC cards")).toBeNull();
    expect(readResource).not.toHaveBeenCalled();
    expect(search).not.toHaveBeenCalledWith("DocumentReference", expect.anything());
    expect(issueCard).not.toHaveBeenCalled();
  });
});

import { describe, expect, it, vi } from "vitest";
import { NoSession } from "./session";
import { checkCardRequest, readCardsFor } from "./cardReads";

describe("readCardTexts", () => {
  it("refuses a request with no session and reads nothing", async () => {
    const session = vi.fn(async () => Promise.reject(new NoSession()));
    const read = vi.fn();
    await expect(readCardsFor({ session }, read, { token: "", hospitalNumber: "CRH-HN-68-104377", texts: [] })).rejects.toBeInstanceOf(NoSession);
    expect(read).not.toHaveBeenCalled();
  });

  it("refuses an empty, blank or over-long hospital number and a non-array of texts", () => {
    const ok = { token: "t", hospitalNumber: "CRH-HN-68-104377", texts: [] };
    expect(checkCardRequest(ok)).toEqual(ok);
    for (const hospitalNumber of ["", "   ", "x".repeat(65)]) expect(() => checkCardRequest({ ...ok, hospitalNumber })).toThrow();
    expect(() => checkCardRequest({ ...ok, texts: "nope" })).toThrow();
    expect(() => checkCardRequest(null)).toThrow();
  });
});

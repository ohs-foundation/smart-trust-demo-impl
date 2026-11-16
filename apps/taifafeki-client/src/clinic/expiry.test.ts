import { describe, expect, it } from "vitest";
import { expiryAt, expiryUntil } from "./expiry";

const MIDNIGHT = Date.UTC(2026, 11, 1) / 1000;

describe("link expiry", () => {
  it("reads midnight UTC as the end of the day before, in any time zone", () => {
    expect(expiryUntil(MIDNIGHT)).toBe("Until the end of 30 Nov 2026");
    expect(expiryAt(MIDNIGHT)).toBe("at the end of 30 Nov 2026");
  });

  it("keeps the full date-time for any other instant", () => {
    expect(expiryUntil(MIDNIGHT + 3600)).toMatch(/^1 Dec 2026, \d\d:\d\d GMT/);
    expect(expiryAt(MIDNIGHT + 3600)).toBe(expiryUntil(MIDNIGHT + 3600));
  });
});

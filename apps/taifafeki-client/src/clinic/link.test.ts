import { WitVhl } from "smart-trust-kotlin-hcert-core";
import { describe, expect, it } from "vitest";
import { issueLink, KEY } from "../testing/link";
import { keyOf, UnreadableLink } from "./link";

describe("theBrowserReadsItsOwnKey", () => {
  it("decodes the key from a link made by hcert-core's issuer", async () => {
    expect(await keyOf(issueLink())).toBe(KEY);
    const other = WitVhl.getInstance().newKey();
    expect(await keyOf(`  ${issueLink({ key: other })}\n`)).toBe(other);
  });

  it("refuses text that is not a link", async () => {
    for (const text of ["", "vhlink:/abc", "HC1:", "HC1:!!!", "HC1:NCFO"]) await expect(keyOf(text)).rejects.toBeInstanceOf(UnreadableLink);
  });
});

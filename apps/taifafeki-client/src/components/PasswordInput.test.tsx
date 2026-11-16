// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PasswordInput } from "./PasswordInput";

afterEach(cleanup);

describe("showAndHidePassword", () => {
  it("toggles the type and label, keeps the value, and does not submit", () => {
    const submit = vi.fn((event: { preventDefault: () => void }) => event.preventDefault());
    render(
      <form onSubmit={submit}>
        <label htmlFor="pw">Password</label>
        <PasswordInput id="pw" />
      </form>,
    );
    const input = screen.getByLabelText("Password") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "s3cret" } });
    expect(input.type).toBe("password");
    fireEvent.click(screen.getByRole("button", { name: "Show password" }));
    expect(input.type).toBe("text");
    expect(input.value).toBe("s3cret");
    const hide = screen.getByRole("button", { name: "Hide password" });
    expect(hide.getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(hide);
    expect(input.type).toBe("password");
    expect(submit).not.toHaveBeenCalled();
  });
});

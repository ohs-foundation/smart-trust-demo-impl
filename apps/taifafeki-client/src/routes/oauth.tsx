import { createFileRoute } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { authorizeParams, login } from "../auth/oauth";
import { getSettings } from "../server/settings";

export const Route = createFileRoute("/oauth")({
  validateSearch: (search: Record<string, unknown>) => search,
  component: SignIn,
});

function SignIn() {
  const search = Route.useSearch();
  const [failure, setFailure] = useState<string>();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      const { medplumBaseUrl } = await getSettings();
      const back = await login(
        medplumBaseUrl,
        authorizeParams(search),
        String(form.get("email")),
        String(form.get("password")),
      );
      window.location.assign(back);
    } catch (error) {
      setFailure(error instanceof Error ? error.message : String(error));
    }
  }

  return (
    <main>
      <h1>Sign in</h1>
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Email <input name="email" type="email" autoComplete="username" required />
        </label>
        <label>
          Password <input name="password" type="password" autoComplete="current-password" required />
        </label>
        <button type="submit">Sign in</button>
        {failure && <p role="alert">{failure}</p>}
      </form>
    </main>
  );
}

import { useMedplum } from "@medplum/react-hooks";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState, type FormEvent } from "react";
import { login } from "../auth/oauth";
import { checkSignIn, exchange } from "../server/settings";

export const Route = createFileRoute("/oauth")({
  validateSearch: (search: Record<string, unknown>) => search,
  component: SignIn,
});

type Ready = { challenge: string; clientId: string; medplumBaseUrl: string };

function SignIn() {
  const search = Route.useSearch();
  const medplum = useMedplum();
  const [ready, setReady] = useState<Ready>();
  const [failure, setFailure] = useState<string>();

  useEffect(() => {
    void checkSignIn({ data: search }).then((guard) =>
      guard.ok ? setReady(guard) : setFailure(guard.message),
    );
  }, [search]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    const form = new FormData(event.currentTarget);
    try {
      const code = await login(
        ready.medplumBaseUrl,
        ready.clientId,
        ready.challenge,
        String(form.get("email")),
        String(form.get("password")),
      );
      const { tokens, error } = await exchange({ data: { code, state: String(search.state) } });
      if (!tokens) throw new Error(error ?? "Sign-in failed");
      await medplum.setActiveLogin(tokens);
      window.location.assign("/");
    } catch (error) {
      setFailure(error instanceof Error ? error.message : String(error));
    }
  }

  if (!ready) {
    return (
      <main>
        {failure ? (
          <p role="alert">
            {failure}. <a href="/signin">Sign in again</a>
          </p>
        ) : (
          <p>Checking sign-in</p>
        )}
      </main>
    );
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

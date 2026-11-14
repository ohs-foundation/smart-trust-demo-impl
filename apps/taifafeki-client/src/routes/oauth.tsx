import { useMedplum } from "@medplum/react-hooks";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useId, useState, type FormEvent } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { ClientHeader } from "@/components/AppHeader";
import { CONTROL, Field } from "@/components/Field";
import { Loading, Problem } from "@/components/Feedback";
import { Page } from "@/components/Page";
import { Panel } from "@/components/Panel";
import { Input } from "@/components/ui/input";
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
  const id = useId();
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
      <Page header={<ClientHeader />} width="narrow">
        {failure ? (
          <Problem>
            {failure}.{" "}
            <a href="/signin" className="underline">
              Sign in again
            </a>
          </Problem>
        ) : (
          <Loading>Checking sign-in</Loading>
        )}
      </Page>
    );
  }
  return (
    <Page
      header={<ClientHeader />}
      width="narrow"
      onSubmit={(event) => void submit(event)}
      actions={
        <ActionBar notice={failure && <Problem>{failure}</Problem>}>
          <Action tone="primary" type="submit">
            Sign In
          </Action>
        </ActionBar>
      }
    >
      <Panel title="Sign in">
        <Field id={`${id}-email`} label="Email">
          <Input id={`${id}-email`} name="email" type="email" autoComplete="username" required className={CONTROL} />
        </Field>
        <Field id={`${id}-password`} label="Password">
          <Input id={`${id}-password`} name="password" type="password" autoComplete="current-password" required className={CONTROL} />
        </Field>
      </Panel>
    </Page>
  );
}

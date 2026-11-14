import { ClientStorage, MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { completeSignIn } from "./auth/complete";
import { getSettings } from "./server/settings";

interface Session {
  medplum: MedplumClient;
  storage: ClientStorage;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [queries] = useState(() => new QueryClient());
  const [session, setSession] = useState<Session>();
  const [failure, setFailure] = useState<string>();

  useEffect(() => {
    void (async () => {
      const settings = await getSettings();
      const storage = new ClientStorage(window.sessionStorage);
      const medplum = new MedplumClient({
        baseUrl: settings.medplumBaseUrl,
        clientId: settings.clientId,
        storage,
        onUnauthenticated: () => window.location.assign("/signin"),
      });
      const params = new URLSearchParams(window.location.search);
      const code = params.get("code");
      if (code && !medplum.getActiveLogin()) {
        await completeSignIn(medplum, storage, document.cookie, code, params.get("state") ?? "");
        window.history.replaceState(null, "", "/");
      }
      setSession({ medplum, storage });
    })().catch((error: unknown) => setFailure(error instanceof Error ? error.message : String(error)));
  }, []);

  if (failure) return <p role="alert">{failure} <a href="/signin">Sign in again</a></p>;
  if (!session) return <p>Loading</p>;
  return (
    <QueryClientProvider client={queries}>
      <MedplumProvider medplum={session.medplum}>{children}</MedplumProvider>
    </QueryClientProvider>
  );
}

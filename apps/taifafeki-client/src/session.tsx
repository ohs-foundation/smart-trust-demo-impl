import { ClientStorage, MedplumClient } from "@medplum/core";
import { MedplumProvider } from "@medplum/react-hooks";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { Loading, Problem } from "@/components/Feedback";
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
      setSession({ medplum, storage });
    })().catch((error: unknown) => setFailure(error instanceof Error ? error.message : String(error)));
  }, []);

  if (failure) {
    return (
      <div className="mx-auto max-w-3xl p-4">
        <Problem>
          {failure}{" "}
          <a href="/signin" className="underline">
            Sign in again
          </a>
        </Problem>
      </div>
    );
  }
  if (!session) return <Loading />;
  return (
    <QueryClientProvider client={queries}>
      <MedplumProvider medplum={session.medplum}>{children}</MedplumProvider>
    </QueryClientProvider>
  );
}

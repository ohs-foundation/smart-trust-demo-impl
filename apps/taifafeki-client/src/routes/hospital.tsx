import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/hospital")({
  validateSearch: (search: Record<string, unknown>) => ({
    organization: typeof search.organization === "string" ? search.organization : "",
  }),
  component: () => (
    <main>
      <h1>Hospital EMR &amp; Print Station (J1)</h1>
    </main>
  ),
});

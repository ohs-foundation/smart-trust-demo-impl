import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/clinic")({
  validateSearch: (search: Record<string, unknown>) => ({
    organization: typeof search.organization === "string" ? search.organization : "",
  }),
  component: () => (
    <main>
      <h1>VHL Viewer (J4.1)</h1>
    </main>
  ),
});

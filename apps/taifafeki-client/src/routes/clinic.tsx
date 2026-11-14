import { createFileRoute } from "@tanstack/react-router";
import { Viewer } from "../clinic/Viewer";

export const Route = createFileRoute("/clinic")({
  validateSearch: (search: Record<string, unknown>) => ({
    organization: typeof search.organization === "string" ? search.organization : "",
  }),
  component: Clinic,
});

function Clinic() {
  const { organization } = Route.useSearch();
  return (
    <main>
      <h1>VHL Viewer (J4.1)</h1>
      <Viewer organization={organization} />
    </main>
  );
}

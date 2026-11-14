import { createFileRoute } from "@tanstack/react-router";
import { WorkplaceHome } from "../Workplaces";

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>) => (search.choose === true || search.choose === "true" ? { choose: true } : {}),
  component: Home,
});

function Home() {
  return <WorkplaceHome choose={Route.useSearch().choose === true} />;
}

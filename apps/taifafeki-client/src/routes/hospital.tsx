import { createFileRoute } from "@tanstack/react-router";
import { Emr } from "../hospital/Emr";

export const Route = createFileRoute("/hospital")({
  validateSearch: (search: Record<string, unknown>) => ({
    organization: typeof search.organization === "string" ? search.organization : "",
    patient: typeof search.patient === "string" ? search.patient : "",
    screen: search.screen === "s1" ? ("s1" as const) : ("emr" as const),
    discharged: typeof search.discharged === "string" ? search.discharged : "",
  }),
  component: Hospital,
});

function Hospital() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  return <Emr search={search} go={(next) => void navigate({ search: { ...search, ...next } })} />;
}

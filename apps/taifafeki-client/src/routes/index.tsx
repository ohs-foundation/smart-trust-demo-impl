import type { Organization, PractitionerRole } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useQuery } from "@tanstack/react-query";
import { Link, createFileRoute } from "@tanstack/react-router";
import { workplaces } from "../workplace";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  const medplum = useMedplum();
  const profile = medplum.getProfile();
  const found = useQuery({
    queryKey: ["workplaces", profile?.id],
    enabled: profile !== undefined,
    queryFn: async () => {
      const roles = await medplum.searchResources("PractitionerRole", {
        practitioner: `Practitioner/${profile?.id}`,
      });
      const organizations = await medplum.searchResources("Organization", {
        _id: roles.flatMap(organizationId).join(","),
      });
      return workplaces(roles, organizations);
    },
  });

  if (!profile) {
    return (
      <main>
        <h1>Taifafeki facility client</h1>
        <a href="/signin">Sign in</a>
      </main>
    );
  }
  return (
    <main>
      <h1>Where are you working?</h1>
      {found.isError && <p role="alert">Your workplaces could not be read.</p>}
      <ul>
        {found.data?.map((workplace) => (
          <li key={workplace.organization.id}>
            <Link to={`/${workplace.configuration}`} search={{ organization: workplace.organization.id ?? "" }}>
              {workplace.title}
            </Link>{" "}
            at {organizationName(workplace.organization)}
          </li>
        ))}
      </ul>
      <button onClick={() => void medplum.signOut().then(() => window.location.assign("/"))}>
        Sign out
      </button>
    </main>
  );
}

function organizationId(role: PractitionerRole): string[] {
  const id = role.organization?.reference?.replace("Organization/", "");
  return id ? [id] : [];
}

function organizationName(organization: Organization) {
  return organization.name ?? organization.id;
}

import { normalizeErrorString, type MedplumClient } from "@medplum/core";
import type { Organization, PractitionerRole } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { workplaces, type Configuration, type Workplace } from "./workplace";

export function useWorkplaces() {
  const medplum = useMedplum();
  const profile = medplum.getProfile();
  return useQuery({
    queryKey: ["workplaces", profile?.id],
    enabled: profile !== undefined,
    queryFn: () => readWorkplaces(medplum, profile?.id ?? ""),
  });
}

async function readWorkplaces(medplum: Pick<MedplumClient, "searchResources">, practitionerId: string): Promise<Workplace[]> {
  const roles = await medplum.searchResources("PractitionerRole", { practitioner: `Practitioner/${practitionerId}` });
  const organizations = await medplum.searchResources("Organization", { _id: roles.flatMap(organizationId).join(",") });
  return workplaces(roles, organizations);
}

export function WorkplacePicker() {
  const medplum = useMedplum();
  const found = useWorkplaces();
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
      <button onClick={() => void medplum.signOut().then(() => window.location.assign("/"))}>Sign out</button>
    </main>
  );
}

interface GateProps {
  configuration: Configuration;
  requested: string;
  children: (organizationId: string) => ReactNode;
}

export function WorkplaceGate({ configuration, requested, children }: GateProps) {
  const medplum = useMedplum();
  const found = useWorkplaces();
  if (!medplum.getProfile()) return <a href="/signin">Sign in</a>;
  if (found.isError) return <p role="alert">{normalizeErrorString(found.error)}</p>;
  if (!found.data) return <p>Loading</p>;
  const workplace = found.data.find((it) => it.configuration === configuration && it.organization.id === requested);
  return workplace?.organization.id ? children(workplace.organization.id) : <WorkplacePicker />;
}

function organizationId(role: PractitionerRole): string[] {
  const id = role.organization?.reference?.replace("Organization/", "");
  return id ? [id] : [];
}

function organizationName(organization: Organization) {
  return organization.name ?? organization.id;
}

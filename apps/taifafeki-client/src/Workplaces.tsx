import { normalizeErrorString, type MedplumClient } from "@medplum/core";
import type { Organization, PractitionerRole } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { ClientHeader } from "@/components/AppHeader";
import { Loading, Problem } from "@/components/Feedback";
import { Page } from "@/components/Page";
import { Panel } from "@/components/Panel";
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
    <Page
      header={<ClientHeader />}
      width="narrow"
      actions={
        <ActionBar>
          <Action onClick={() => void medplum.signOut().then(() => window.location.assign("/"))}>Sign Out</Action>
        </ActionBar>
      }
    >
      <Panel title="Where are you working?">
        {found.isError && <Problem>Your workplaces could not be read.</Problem>}
        <p className="font-mono text-3xs tracking-wide text-slate-500 uppercase">Working at</p>
        <ul className="flex flex-col">
          {found.data?.map((workplace) => (
            <li key={workplace.organization.id}>
              <Link
                to={`/${workplace.configuration}`}
                search={{ organization: workplace.organization.id ?? "" }}
                className="group flex flex-wrap items-center gap-x-2.5 gap-y-0.5 rounded-control px-1 py-1.5 text-ui hover:bg-taifa-soft focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
              >
                <span className="size-4 shrink-0 rounded-full border-2 border-slate-400 group-hover:border-brand-taifa group-hover:bg-brand-taifa group-hover:shadow-[inset_0_0_0_2px_white]" />
                {organizationName(workplace.organization)}
                <small className="font-mono text-2xs text-slate-500">{workplace.title}</small>
              </Link>
            </li>
          ))}
        </ul>
      </Panel>
    </Page>
  );
}

export function SignInPrompt() {
  return (
    <Page
      header={<ClientHeader />}
      width="narrow"
      actions={
        <ActionBar>
          <Action tone="primary" asChild>
            <a href="/signin">Sign In</a>
          </Action>
        </ActionBar>
      }
    >
      <Panel title="Sign in">
        <p className="text-xs text-slate-600">Sign in with your Medplum account to open your workplace.</p>
      </Panel>
    </Page>
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
  if (!medplum.getProfile()) return <SignInPrompt />;
  if (found.isError) return <Problem>{normalizeErrorString(found.error)}</Problem>;
  if (!found.data) return <Loading />;
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

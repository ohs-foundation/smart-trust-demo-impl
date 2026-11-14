import { useMedplum } from "@medplum/react-hooks";
import { createFileRoute } from "@tanstack/react-router";
import { SignInPrompt, WorkplacePicker } from "../Workplaces";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return useMedplum().getProfile() ? <WorkplacePicker /> : <SignInPrompt />;
}

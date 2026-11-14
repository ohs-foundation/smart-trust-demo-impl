import { useMedplum } from "@medplum/react-hooks";
import { createFileRoute } from "@tanstack/react-router";
import { WorkplacePicker } from "../Workplaces";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  if (!useMedplum().getProfile()) {
    return (
      <main>
        <h1>Taifafeki facility client</h1>
        <a href="/signin">Sign in</a>
      </main>
    );
  }
  return <WorkplacePicker />;
}

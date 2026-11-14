import { Check, Syringe } from "lucide-react";
import type { ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { Facts } from "@/components/Facts";
import { Problem } from "@/components/Feedback";
import { Page } from "@/components/Page";
import { Panel } from "@/components/Panel";
import { PatientBanner } from "@/components/PatientBanner";
import { RecordCard, RecordRow } from "@/components/RecordCard";
import { ResultPanel } from "@/components/ResultPanel";
import { StatusChip } from "@/components/StatusChip";
import type { SummaryView } from "./summary";

export type Receipt = { state: "pending" } | { state: "recorded" } | { state: "failed"; reason: string };

interface Props {
  header: ReactNode;
  view: SummaryView;
  receipt: Receipt;
  passcode: boolean;
  onClose: () => void;
}

export function SummaryScreen({ header, view, receipt, passcode, onClose }: Props) {
  const { patient } = view;
  const count = view.immunizations.length;
  const verified = view.immunizations.filter((dose) => dose.primarySource === true).length;
  return (
    <Page
      header={header}
      width="narrow"
      actions={
        <ActionBar>
          <Action onClick={onClose}>Close Summary</Action>
        </ActionBar>
      }
    >
      <ResultPanel tone="pass" title={`${passcode ? "Passcode Verified (✓) • " : ""}Read-Only Zero-Retention Clinical Session`}>
        Viewed here only; nothing is saved.{" "}
        {receipt.state === "pending" && "Recording the receipt."}
        {receipt.state === "recorded" && <span role="status">Receipt recorded.</span>}
      </ResultPanel>
      {receipt.state === "failed" && <Problem>The receipt could not be recorded: {receipt.reason}</Problem>}
      <PatientBanner surface="light" name={patient.name} chips={<StatusChip tone="info">Born {patient.birthDate}</StatusChip>} subtitle={[`Sex: ${patient.sex}`, ...patient.contacts].join(" • ")}>
        <Facts
          items={[
            { label: "Source", value: view.source },
            { label: "Summary of", value: view.date.replace("T", " ").slice(0, 16), mono: true },
          ]}
        />
      </PatientBanner>
      <RecordCard
        title="International Patient Summary (IPS) — Immunization History"
        icon={Syringe}
        summary={
          <StatusChip tone="success">
            {count} {verified === count ? "Verified " : ""}
            {count === 1 ? "Immunization" : "Immunizations"}
          </StatusChip>
        }
      >
        {view.immunizations.map((dose, index) => (
          <RecordRow
            key={index}
            icon={Syringe}
            label={dose.vaccine}
            code={[dose.product && `PreQual: ${dose.product}`, dose.schedule && `Onesan: ${dose.schedule}`, dose.cvx && `CVX: ${dose.cvx}`].filter(Boolean).join(" • ")}
            detail={[dose.date.slice(0, 10), dose.dose && `Dose ${dose.dose}`, dose.lot && `Lot: ${dose.lot}`, dose.location, dose.performer].filter(Boolean).join(" • ")}
            status={
              dose.primarySource !== undefined && (
                <StatusChip tone={dose.primarySource ? "success" : "warning"} icon={dose.primarySource ? Check : undefined}>
                  primarySource: {String(dose.primarySource)}
                </StatusChip>
              )
            }
          />
        ))}
      </RecordCard>
      <Panel>
        <Facts
          items={[
            { label: "Allergies & Intolerances (IPS Section)", value: view.allergies.join(", ") || "Not in the summary" },
            { label: "Problems (IPS Section)", value: view.problems.join(", ") || "Not in the summary" },
          ]}
        />
      </Panel>
    </Page>
  );
}

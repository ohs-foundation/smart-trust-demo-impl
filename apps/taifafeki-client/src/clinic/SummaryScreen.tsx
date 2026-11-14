import { Syringe } from "lucide-react";
import type { ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { Facts } from "@/components/Facts";
import { Problem } from "@/components/Feedback";
import { Page } from "@/components/Page";
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
      <PatientBanner surface="light" name={patient.name} chips={<StatusChip tone="info">Born {patient.birthDate}</StatusChip>} subtitle={`Sex: ${patient.sex}`}>
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
        summary={<StatusChip tone="success">{count} {count === 1 ? "Immunization" : "Immunizations"}</StatusChip>}
      >
        {view.immunizations.map((dose, index) => (
          <RecordRow
            key={index}
            icon={Syringe}
            label={dose.vaccine}
            detail={[dose.date.slice(0, 10), dose.performer].filter(Boolean).join(" • ")}
            status={dose.dose && <StatusChip tone="info">Dose {dose.dose}</StatusChip>}
          />
        ))}
      </RecordCard>
    </Page>
  );
}

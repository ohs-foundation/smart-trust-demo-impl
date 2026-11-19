import { normalizeErrorString } from "@medplum/core";
import type { Immunization } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useQuery } from "@tanstack/react-query";
import { FileText, Info } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { Facts } from "@/components/Facts";
import { Loading, Notice, Problem } from "@/components/Feedback";
import { Page } from "@/components/Page";
import { Note, Panel } from "@/components/Panel";
import { QrCard } from "@/components/QrCard";
import { StatusChip } from "@/components/StatusChip";
import { cn } from "@/lib/utils";
import { readCards, readChart } from "./api";
import { compareDoses, type Card } from "./cards";
import { PRODUCT_SYSTEM, hospitalNumberOf } from "./records";

interface Printing {
  hospitalNumber: string;
  cards: Card[];
  warnings: string[];
  doses: Immunization[];
}

export function usePrintCards(patientId: string) {
  const medplum = useMedplum();
  return useQuery({
    queryKey: ["print", patientId],
    queryFn: async (): Promise<Printing> => {
      const { patient, doses } = await readChart(medplum, patientId);
      const hospitalNumber = hospitalNumberOf(patient);
      const cards = await readCards(medplum, patient, doses, medplum.getAccessToken() ?? "");
      const given = (card: Card) => doses.findIndex((dose) => dose.vaccineCode?.coding?.some((it) => it.system === PRODUCT_SYSTEM && it.code === card.product));
      cards.sort((a, b) => given(a) - given(b));
      return { hospitalNumber, cards, warnings: compareDoses(cards, doses), doses };
    },
  });
}

interface Props {
  header: (subtitle?: string) => ReactNode;
  patientId: string;
  onBack: () => void;
}

export function PrintStation({ header, patientId, onBack }: Props) {
  const print = usePrintCards(patientId);
  const [inspecting, setInspecting] = useState(false);
  const [printed, setPrinted] = useState(0);
  const sent = useRef(0);
  useEffect(() => {
    const done = () => setPrinted(sent.current);
    window.addEventListener("afterprint", done);
    return () => window.removeEventListener("afterprint", done);
  }, []);
  if (print.isError) return <Problem>{normalizeErrorString(print.error)}</Problem>;
  if (!print.data) return <Loading />;
  const count = print.data.cards.length;
  return (
    <Page
      header={header(`Print preview • ${count} wallet ${count === 1 ? "card" : "cards"}`)}
      actions={
        <ActionBar>
          <Action onClick={onBack}>Back</Action>
          {count > 0 && (
            <Action aria-pressed={inspecting} onClick={() => setInspecting(!inspecting)}>
              Inspect Raw DVCMinPreQual & CBOR
            </Action>
          )}
          {count > 0 && (
            <Action
              tone={printed ? "secondary" : "primary"}
              className={cn(printed > 0 && "border-ok-line bg-ok-soft text-ok-ink")}
              onClick={() => {
                sent.current = count;
                window.print();
              }}
            >
              {printed ? `✓ ${printed} DVC ${printed === 1 ? "Card" : "Cards"} Printed` : `Print ${count} ${count === 1 ? "Card" : "Cards"}`}
            </Action>
          )}
        </ActionBar>
      }
    >
      <div className="print:hidden">
        <Note icon={Info}>One card per dose, one card to a printed page.</Note>
      </div>
      {inspecting && <RawPayloads cards={print.data.cards} />}
      <PrintCards {...print.data} />
    </Page>
  );
}

function RawPayloads({ cards }: { cards: Card[] }) {
  return (
    <div className="flex flex-col gap-3 print:hidden">
      {cards.map((card, at) => (
        <Panel key={card.doseId} title={`Card ${at + 1} of ${cards.length} • DVCMinPreQual CBOR • ${card.alg}`} icon={FileText}>
          <Facts
            items={[
              { label: "WHO PreQual product code", value: card.product, mono: true },
              { label: "Lot", value: card.lot, mono: true },
              { label: "Date", value: card.date, mono: true },
              { label: "Issuer country", value: card.issuer, mono: true },
              { label: "Key ID (kid)", value: card.kid, mono: true },
              { label: "Holder", value: `${card.holder} • born ${card.birth}` },
            ]}
          />
        </Panel>
      ))}
    </div>
  );
}

export function PrintCards({ hospitalNumber, cards, warnings, doses }: Printing) {
  return (
    <>
      {!hospitalNumber.trim() && <Problem>This patient has no hospital number, so no cards can be looked up.</Problem>}
      {hospitalNumber.trim() && cards.length === 0 && <p className="text-xs text-slate-500">No signed cards for this hospital number</p>}
      {warnings.length > 0 && (
        <Notice role="alert" title="The cards and the entered doses differ" className="print:hidden">
          <ul className="list-disc pl-4">
            {warnings.map((warning) => <li key={warning}>{warning}</li>)}
          </ul>
        </Notice>
      )}
      {cards.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 print:block">
          {cards.map((card, at) => (
            <QrCard
              key={card.doseId}
              text={card.hc1}
              holder={card.holder}
              holderNote={`Born ${card.birth}`}
              label={`Vaccine: ${productName(card.product, doses)}`}
              badge={card.signer ? <StatusChip tone="info">Signed by {card.signer}</StatusChip> : <StatusChip tone="warning">Signer not on a trust list</StatusChip>}
              caption={`Card ${at + 1} of ${cards.length} • Key ID: ${card.kid} • Issuer ${card.issuer} • ${card.alg}`}
              alt={`Vaccination card QR for ${card.product}`}
            />
          ))}
        </div>
      )}
    </>
  );
}

function productName(code: string, doses: Immunization[]): string {
  const coding = doses.flatMap((dose) => dose.vaccineCode?.coding ?? []).find((it) => it.system === PRODUCT_SYSTEM && it.code === code);
  return coding?.display ?? code;
}

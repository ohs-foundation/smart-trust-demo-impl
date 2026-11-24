import { normalizeErrorString } from "@medplum/core";
import type { Immunization, Patient } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IdCard } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Action } from "@/components/ActionBar";
import { Loading, Problem } from "@/components/Feedback";
import { Panel } from "@/components/Panel";
import { QrCode } from "@/components/QrCode";
import { RecordRow } from "@/components/RecordCard";
import { StatusChip } from "@/components/StatusChip";
import { issueCards, readStoredCards, reissueCard } from "./api";
import type { Card } from "./cards";
import { PrintCards, RawPayloads, productName } from "./PrintStation";
import { hospitalNumberOf } from "./records";

interface Props {
  patient: Patient;
  doses: Immunization[];
}

export function DvcCards({ patient, doses }: Props) {
  const medplum = useMedplum();
  const queries = useQueryClient();
  const [shown, setShown] = useState<string>();
  const [full, setFull] = useState<string>();
  const [inspecting, setInspecting] = useState(false);
  const [confirming, setConfirming] = useState<string>();
  const token = medplum.getAccessToken() ?? "";
  const key = ["dvc-cards", patient.id];
  const stored = useQuery({ queryKey: key, queryFn: () => readStoredCards(medplum, patient, token) });
  const refresh = async () => {
    setConfirming(undefined);
    await Promise.all([queries.invalidateQueries({ queryKey: key }), queries.invalidateQueries({ queryKey: ["print", patient.id] })]);
  };
  const issue = useMutation({ mutationFn: () => issueCards(medplum, patient, doses, token), onSuccess: refresh });
  const reissue = useMutation({ mutationFn: (doseId: string) => reissueCard(medplum, patient, doseId, token), onSuccess: refresh });

  const hospitalNumber = hospitalNumberOf(patient);
  if (stored.isError) return <Problem>{normalizeErrorString(stored.error)}</Problem>;
  if (!stored.data) return <Loading />;
  const { superseded } = stored.data;
  const at = (card: Card) => doses.findIndex((dose) => dose.id === card.doseId);
  const current = [...stored.data.current].sort((a, b) => at(a) - at(b));
  const unissued = doses.filter((dose) => dose.status === "completed" && !current.some((card) => card.doseId === dose.id));
  const printable = { hospitalNumber, warnings: [], doses };
  const error = issue.error ?? reissue.error;
  return (
    <>
      <div className="print:hidden">
        <Panel title="DVC cards" icon={IdCard}>
          {!hospitalNumber.trim() && <p className="text-xs text-slate-500">This patient has no hospital number, so no cards can be looked up.</p>}
          {hospitalNumber.trim() && current.length === 0 && unissued.length === 0 && <p className="text-xs text-slate-500">No signed cards and no completed doses.</p>}
          {current.length > 0 && (
            <div className="flex flex-wrap gap-2">
              <Action aria-pressed={inspecting} onClick={() => setInspecting(!inspecting)}>Inspect Raw DVCMinPreQual & CBOR</Action>
              <Action onClick={() => window.print()}>Print cards</Action>
            </div>
          )}
          {inspecting && <RawPayloads cards={current} />}
          {current.length > 0 && (
            <ul className="flex flex-col gap-1.5">
              {current.map((card) => (
                <CardRow
                  key={card.doseId}
                  card={card}
                  older={superseded.filter((it) => it.doseId === card.doseId)}
                  doses={doses}
                  shown={shown === card.doseId}
                  confirming={confirming === card.doseId}
                  busy={reissue.isPending}
                  onShow={() => setShown(shown === card.doseId ? undefined : card.doseId)}
                  onAsk={() => setConfirming(card.doseId)}
                  onCancel={() => setConfirming(undefined)}
                  onConfirm={() => reissue.mutate(card.doseId)}
                />
              ))}
            </ul>
          )}
          {shown && <ShownQr cards={current} doses={doses} doseId={shown} onFull={() => setFull(shown)} />}
          {unissued.length > 0 && (
            <div className="flex flex-wrap items-center gap-2">
              <p className="text-xs text-slate-500">{unissued.length} completed {unissued.length === 1 ? "dose has" : "doses have"} no card.</p>
              <Action tone="primary" disabled={issue.isPending} onClick={() => issue.mutate()}>Issue DVC cards</Action>
            </div>
          )}
          {error && <Problem>{normalizeErrorString(error)}</Problem>}
        </Panel>
      </div>
      {full && <FullScreenQr cards={current} doses={doses} start={full} onClose={() => setFull(undefined)} />}
      <div className="hidden print:block">
        <PrintCards {...printable} cards={current} />
      </div>
    </>
  );
}

interface RowProps {
  card: Card;
  older: Card[];
  doses: Immunization[];
  shown: boolean;
  confirming: boolean;
  busy: boolean;
  onShow: () => void;
  onAsk: () => void;
  onCancel: () => void;
  onConfirm: () => void;
}

function CardRow({ card, older, doses, shown, confirming, busy, onShow, onAsk, onCancel, onConfirm }: RowProps) {
  return (
    <>
      <RecordRow
        label={productName(card.product, doses)}
        code={`Lot ${card.lot}`}
        detail={`Given ${card.date}`}
        status={
          <span className="flex flex-wrap justify-end gap-1.5">
            <Action aria-pressed={shown} className="min-h-0 px-2 py-1 text-2xs" onClick={onShow}>Show QR</Action>
            {confirming ? (
              <>
                <Action className="min-h-0 px-2 py-1 text-2xs" disabled={busy} onClick={onConfirm}>Confirm re-issue</Action>
                <Action className="min-h-0 px-2 py-1 text-2xs" onClick={onCancel}>Cancel</Action>
              </>
            ) : (
              <Action className="min-h-0 px-2 py-1 text-2xs" onClick={onAsk}>Re-issue card</Action>
            )}
          </span>
        }
      />
      {older.map((it, at) => (
        <RecordRow key={`${it.hc1}-${at}`} label={productName(it.product, doses)} code={`Lot ${it.lot}`} detail={`Given ${it.date}`} status={<StatusChip tone="neutral">Superseded</StatusChip>} />
      ))}
    </>
  );
}

function labelOf(cards: Card[], at: number, doses: Immunization[]): string {
  const card = cards[at];
  return `Card ${at + 1} of ${cards.length} · ${productName(card?.product ?? "", doses)} · lot ${card?.lot}`;
}

function ShownQr({ cards, doses, doseId, onFull }: { cards: Card[]; doses: Immunization[]; doseId: string; onFull: () => void }) {
  const at = cards.findIndex((card) => card.doseId === doseId);
  const card = cards[at];
  if (!card) return null;
  const label = labelOf(cards, at, doses);
  return (
    <figure className="flex flex-col items-center gap-2">
      <QrCode text={card.hc1} alt={`Vaccination card QR for ${card.product}`} size="min(100%, 24rem)" />
      <figcaption className="text-xs text-slate-600">{label}</figcaption>
      <Action onClick={onFull}>Full screen</Action>
    </figure>
  );
}

function FullScreenQr({ cards, doses, start, onClose }: { cards: Card[]; doses: Immunization[]; start: string; onClose: () => void }) {
  const [at, setAt] = useState(Math.max(0, cards.findIndex((card) => card.doseId === start)));
  const overlay = useRef<HTMLDivElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  const leave = useRef(onClose);
  leave.current = onClose;
  const card = cards[at];
  useEffect(() => {
    const element = overlay.current;
    void element?.requestFullscreen?.().catch(() => undefined);
    close.current?.focus();
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && leave.current();
    const onChange = () => !document.fullscreenElement && leave.current();
    window.addEventListener("keydown", onKey);
    document.addEventListener("fullscreenchange", onChange);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("fullscreenchange", onChange);
      if (document.fullscreenElement === element) void document.exitFullscreen().catch(() => undefined);
    };
  }, []);
  if (!card) return null;
  return (
    <div ref={overlay} role="dialog" aria-modal="true" aria-label="DVC card QR, full screen" className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-2 bg-white print:hidden">
      <QrCode text={card.hc1} alt={`Vaccination card QR for ${card.product}`} size="calc(min(100vw, 100dvh) - 7rem)" />
      <p className="text-sm text-slate-700" aria-live="polite">{labelOf(cards, at, doses)}</p>
      <div className="absolute top-3 right-3 flex gap-2">
        {cards.length > 1 && (
          <>
            <Action onClick={() => setAt((at + cards.length - 1) % cards.length)}>Previous</Action>
            <Action onClick={() => setAt((at + 1) % cards.length)}>Next</Action>
          </>
        )}
        <Action ref={close} onClick={onClose}>Close</Action>
      </div>
    </div>
  );
}

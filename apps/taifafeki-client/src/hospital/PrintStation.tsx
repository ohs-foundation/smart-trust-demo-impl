import { normalizeErrorString } from "@medplum/core";
import { useMedplum } from "@medplum/react-hooks";
import { useQuery } from "@tanstack/react-query";
import QRCode from "qrcode";
import { readCards, readChart } from "./api";
import { compareDoses, type Card } from "./cards";
import { hospitalNumberOf } from "./records";

const PRINT_STYLE = `
@media print {
  header, .no-print { display: none; }
  .card { break-after: page; }
}
`;

export function PrintStation({ patientId, onBack }: { patientId: string; onBack: () => void }) {
  const medplum = useMedplum();
  const print = useQuery({
    queryKey: ["print", patientId],
    queryFn: async () => {
      const { patient, doses } = await readChart(medplum, patientId);
      const cards = await readCards(medplum, hospitalNumberOf(patient));
      return { cards, warnings: compareDoses(cards, doses) };
    },
  });

  if (print.isError) return <p role="alert">{normalizeErrorString(print.error)}</p>;
  if (!print.data) return <p>Loading</p>;
  const { cards, warnings } = print.data;
  return (
    <section>
      <style>{PRINT_STYLE}</style>
      <div className="no-print">
        <p><button onClick={onBack}>Back</button></p>
        <h2>Print cards</h2>
        {cards.length === 0 && <p>No signed cards for this hospital number</p>}
        {warnings.length > 0 && (
          <ul role="alert">
            {warnings.map((warning) => <li key={warning}>{warning}</li>)}
          </ul>
        )}
        {cards.length > 0 && <p><button onClick={() => window.print()}>Print</button></p>}
      </div>
      {cards.map((card) => <PrintableCard key={card.doseId} card={card} />)}
    </section>
  );
}

function PrintableCard({ card }: { card: Card }) {
  const qr = useQuery({ queryKey: ["qr", card.hc1], queryFn: () => QRCode.toDataURL(card.hc1, { margin: 2, width: 320 }) });
  return (
    <article className="card">
      {qr.data && <img src={qr.data} alt={`Vaccination card QR for ${card.product}`} width={320} height={320} />}
      <h3>{card.holder}</h3>
      <dl>
        <dt>Born</dt><dd>{card.birth}</dd>
        <dt>Vaccine</dt><dd>{card.product}</dd>
        <dt>Given</dt><dd>{card.date}</dd>
        <dt>Lot</dt><dd>{card.lot}</dd>
      </dl>
    </article>
  );
}

import type { SummaryView } from "./summary";

export type Receipt = { state: "pending" } | { state: "recorded" } | { state: "failed"; reason: string };

interface Props {
  view: SummaryView;
  receipt: Receipt;
  onClose: () => void;
}

export function SummaryScreen({ view, receipt, onClose }: Props) {
  const { patient } = view;
  return (
    <section>
      <h2>Patient summary</h2>
      <p>
        {patient.name} · born {patient.birthDate} · {patient.sex}
      </p>
      <p>
        Summary of {view.date} from {view.source}. Viewed here only; nothing is saved.
      </p>
      <h3>Immunizations</h3>
      <table>
        <thead>
          <tr>
            <th>Vaccine</th>
            <th>Date</th>
            <th>Dose</th>
            <th>Given by</th>
          </tr>
        </thead>
        <tbody>
          {view.immunizations.map((dose, index) => (
            <tr key={index}>
              <td>{dose.vaccine}</td>
              <td>{dose.date}</td>
              <td>{dose.dose}</td>
              <td>{dose.performer}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {receipt.state === "recorded" && <p role="status">Receipt recorded.</p>}
      {receipt.state === "failed" && <p role="alert">The receipt could not be recorded: {receipt.reason}</p>}
      <p>
        <button onClick={onClose}>Close Summary</button>
      </p>
    </section>
  );
}

import { normalizeErrorString } from "@medplum/core";
import type { HumanName, Immunization, Patient } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { addDoses, readChart, readContext, readNewborns, readProducts, registerNewborn, type Product } from "./api";
import { PrintStation } from "./PrintStation";
import { hospitalNumberOf, type Context, type Dose, type Newborn, type TimedPatient } from "./records";

export interface EmrSearch {
  organization: string;
  patient: string;
  screen: "emr" | "s1" | "print";
  discharged: string;
}

interface Props {
  search: EmrSearch;
  go: (next: Partial<EmrSearch>) => void;
}

interface DoseDraft {
  productCode: string;
  occurredAt: string;
  lot: string;
}

const GENDERS = ["male", "female", "other", "unknown"] as const;

export function Emr({ search, go }: Props) {
  const medplum = useMedplum();
  const profile = medplum.getProfile();
  const context = useQuery({
    queryKey: ["hospital-context", search.organization, profile?.id],
    enabled: profile?.id !== undefined && search.organization !== "",
    queryFn: () => readContext(medplum, search.organization, profile?.id ?? ""),
  });
  const products = useQuery({ queryKey: ["products"], queryFn: () => readProducts(medplum) });

  if (!profile) return <a href="/signin">Sign in</a>;
  if (context.isError) return <Failure error={context.error} />;
  if (!context.data) return <p>Loading</p>;
  const { hospital, practitioner, ...ids } = context.data;
  const heading = (
    <header>
      <h1>Hospital EMR &amp; Print Station (J1)</h1>
      <p>
        {hospital.name} · {nameOf(practitioner)} · <a href="/">Change workplace</a>
      </p>
    </header>
  );

  if (search.patient && search.screen === "s1") {
    return (
      <main>
        {heading}
        <DischargeSummary patientId={search.patient} onBack={() => go({ screen: "emr" })} onDone={(name) => go({ patient: "", screen: "emr", discharged: name })} />
      </main>
    );
  }
  if (search.patient && search.screen === "print") {
    return (
      <main>
        {heading}
        <PrintStation patientId={search.patient} onBack={() => go({ screen: "emr" })} />
      </main>
    );
  }
  if (search.patient) {
    return (
      <main>
        {heading}
        <PatientChart
          patientId={search.patient}
          context={ids}
          products={products.data ?? []}
          onList={() => go({ patient: "" })}
          onSummary={() => go({ screen: "s1" })}
          onPrint={() => go({ screen: "print" })}
        />
      </main>
    );
  }
  return (
    <main>
      {heading}
      {search.discharged && <p role="status">Discharged: {search.discharged}</p>}
      <Register context={ids} products={products.data ?? []} onRegistered={(patient) => go({ patient, discharged: "" })} />
      <Newborns hospitalId={search.organization} onOpen={(patient) => go({ patient, discharged: "" })} />
    </main>
  );
}

function Newborns({ hospitalId, onOpen }: { hospitalId: string; onOpen: (patientId: string) => void }) {
  const medplum = useMedplum();
  const newborns = useQuery({ queryKey: ["newborns", hospitalId], queryFn: () => readNewborns(medplum, hospitalId) });
  return (
    <section>
      <h2>Newborns</h2>
      {newborns.isError && <Failure error={newborns.error} />}
      {newborns.data?.length === 0 && <p>No newborns registered yet.</p>}
      <ul>
        {newborns.data?.map((patient) => (
          <li key={patient.id}>
            <button onClick={() => onOpen(patient.id ?? "")}>{nameOf(patient)}</button> · {hospitalNumberOf(patient)} · born {patient.birthDate}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Register({ context, products, onRegistered }: { context: Context; products: Product[]; onRegistered: (patientId: string) => void }) {
  const medplum = useMedplum();
  const queries = useQueryClient();
  const [given, setGiven] = useState("");
  const [family, setFamily] = useState("");
  const [gender, setGender] = useState<Newborn["gender"]>("male");
  const [birth, setBirth] = useState(localNow);
  const [hospitalNumber, setHospitalNumber] = useState("");
  const [doses, setDoses] = useState<DoseDraft[]>();
  const rows = doses ?? defaultDoses(products, birth);

  const register = useMutation({
    mutationFn: () =>
      registerNewborn(medplum, { given, family, gender, birth, hospitalNumber }, toDoses(rows, products), context),
    onSuccess: async (result) => {
      await queries.invalidateQueries({ queryKey: ["newborns"] });
      const id = result.entry?.[0]?.response?.location?.split("/")[1] ?? "";
      onRegistered(id);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    register.mutate();
  }

  return (
    <section>
      <h2>Register a newborn</h2>
      <form onSubmit={submit}>
        <label>Given name <input required value={given} onChange={(e) => setGiven(e.target.value)} /></label>{" "}
        <label>Family name <input required value={family} onChange={(e) => setFamily(e.target.value)} /></label>{" "}
        <label>
          Sex{" "}
          <select value={gender} onChange={(e) => setGender(e.target.value as Newborn["gender"])}>
            {GENDERS.map((it) => <option key={it}>{it}</option>)}
          </select>
        </label>{" "}
        <label>Born <input required type="datetime-local" max={localNow()} value={birth} onChange={(e) => setBirth(e.target.value)} /></label>{" "}
        <label>
          Hospital number{" "}
          <input required placeholder="CRH-HN-YY-NNNNNN" value={hospitalNumber} onChange={(e) => setHospitalNumber(e.target.value)} />
        </label>
        <h3>Birth doses</h3>
        <DoseRows rows={rows} products={products} birth={birth} onChange={setDoses} />
        <button type="submit" disabled={register.isPending}>Register</button>
        {register.isError && <Failure error={register.error} />}
      </form>
    </section>
  );
}

function PatientChart({ patientId, context, products, onList, onSummary, onPrint }: { patientId: string; context: Context; products: Product[]; onList: () => void; onSummary: () => void; onPrint: () => void }) {
  const medplum = useMedplum();
  const queries = useQueryClient();
  const chart = useQuery({ queryKey: ["chart", patientId], queryFn: () => readChart(medplum, patientId) });
  const [rows, setRows] = useState<DoseDraft[]>([]);
  const add = useMutation({
    mutationFn: () => addDoses(medplum, chart.data?.patient as TimedPatient, toDoses(rows, products), context),
    onSuccess: async () => {
      setRows([]);
      await queries.invalidateQueries({ queryKey: ["chart", patientId] });
    },
  });

  if (chart.isError) return <Failure error={chart.error} />;
  if (!chart.data) return <p>Loading</p>;
  const { patient, doses } = chart.data;
  const birth = birthLocal(patient);
  return (
    <section>
      <p><button onClick={onList}>Back to the newborns</button></p>
      <h2>{nameOf(patient)}</h2>
      <p>{hospitalNumberOf(patient)} · {patient.gender} · born {birth.replace("T", " ")}</p>
      <h3>Doses</h3>
      <DoseTable doses={doses} />
      <form
        onSubmit={(event) => {
          event.preventDefault();
          add.mutate();
        }}
      >
        <h3>Add a dose</h3>
        <DoseRows rows={rows} products={products} birth={birth} onChange={setRows} />
        {rows.length > 0 && <button type="submit" disabled={add.isPending}>Save doses</button>}
        {add.isError && <Failure error={add.error} />}
      </form>
      <p><button onClick={onSummary}>Discharge summary</button> <button onClick={onPrint}>Print cards</button></p>
    </section>
  );
}

function DischargeSummary({ patientId, onBack, onDone }: { patientId: string; onBack: () => void; onDone: (name: string) => void }) {
  const medplum = useMedplum();
  const chart = useQuery({ queryKey: ["chart", patientId], queryFn: () => readChart(medplum, patientId) });
  if (chart.isError) return <Failure error={chart.error} />;
  if (!chart.data) return <p>Loading</p>;
  const { patient, doses } = chart.data;
  return (
    <section>
      <h2>Discharge summary</h2>
      <dl>
        <dt>Patient</dt><dd>{nameOf(patient)}</dd>
        <dt>Hospital number</dt><dd>{hospitalNumberOf(patient)}</dd>
        <dt>Sex</dt><dd>{patient.gender}</dd>
        <dt>Born</dt><dd>{birthLocal(patient).replace("T", " ")}</dd>
      </dl>
      <h3>Birth doses</h3>
      <DoseTable doses={doses} />
      <button onClick={() => onDone(nameOf(patient))}>Complete Discharge</button>{" "}
      <button onClick={onBack}>Back</button>
    </section>
  );
}

function DoseTable({ doses }: { doses: Immunization[] }) {
  if (doses.length === 0) return <p>No doses recorded.</p>;
  return (
    <table>
      <thead>
        <tr><th>Product</th><th>CVX</th><th>Given</th><th>Lot</th><th>Dose</th></tr>
      </thead>
      <tbody>
        {doses.map((dose) => {
          const codings = dose.vaccineCode?.coding ?? [];
          return (
            <tr key={dose.id}>
              <td>{codings[0]?.display ?? codings[0]?.code}</td>
              <td>{codings.find((it) => it.system?.endsWith("/cvx"))?.code ?? "none"}</td>
              <td>{dose.occurrenceDateTime?.replace("T", " ")}</td>
              <td>{dose.lotNumber}</td>
              <td>{dose.protocolApplied?.[0]?.doseNumberPositiveInt}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function DoseRows({ rows, products, birth, onChange }: { rows: DoseDraft[]; products: Product[]; birth: string; onChange: (rows: DoseDraft[]) => void }) {
  const edit = (index: number, change: Partial<DoseDraft>) =>
    onChange(rows.map((row, at) => (at === index ? { ...row, ...change } : row)));
  return (
    <>
      {rows.map((row, index) => (
        <p key={index}>
          <label>
            Product{" "}
            <select value={row.productCode} onChange={(e) => edit(index, { productCode: e.target.value })}>
              {products.map((it) => <option key={it.code} value={it.code}>{it.display}</option>)}
            </select>
          </label>{" "}
          <label>Given <input required type="datetime-local" min={birth} max={localNow()} value={row.occurredAt} onChange={(e) => edit(index, { occurredAt: e.target.value })} /></label>{" "}
          <label>Lot <input required value={row.lot} onChange={(e) => edit(index, { lot: e.target.value })} /></label>{" "}
          <button type="button" onClick={() => onChange(rows.filter((_, at) => at !== index))}>Remove</button>
        </p>
      ))}
      <button type="button" onClick={() => onChange([...rows, ...defaultDoses(products, birth).slice(0, 1)])}>Add another dose</button>
    </>
  );
}

function Failure({ error }: { error: unknown }) {
  return <p role="alert">{normalizeErrorString(error)}</p>;
}

function defaultDoses(products: Product[], birth: string): DoseDraft[] {
  const at = localNow() > birth ? localNow() : birth;
  return ["vp-hepb-peds-monovalent", "vp-bcg-freeze-dried"].flatMap((code) =>
    products.some((it) => it.code === code) ? [{ productCode: code, occurredAt: at, lot: "" }] : [],
  );
}

function toDoses(rows: DoseDraft[], products: Product[]): Dose[] {
  return rows.map((row) => ({
    productCode: row.productCode,
    productDisplay: products.find((it) => it.code === row.productCode)?.display ?? row.productCode,
    occurredAt: row.occurredAt,
    lot: row.lot,
  }));
}

function localNow(): string {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function birthLocal(patient: TimedPatient): string {
  const time = patient._birthDate?.extension?.[0]?.valueDateTime;
  if (!time) return patient.birthDate ?? "";
  const at = new Date(time);
  return new Date(at.getTime() - at.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function nameOf(person: { name?: HumanName[] }): string {
  const name = person.name?.[0];
  return [name?.given?.join(" "), name?.family].filter(Boolean).join(" ");
}


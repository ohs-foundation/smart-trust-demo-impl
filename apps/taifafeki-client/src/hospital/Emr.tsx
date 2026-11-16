import { normalizeErrorString } from "@medplum/core";
import type { HumanName, Immunization, Organization, Practitioner } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Baby, HospitalIcon, Plus, Printer, Syringe } from "lucide-react";
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { AppHeader, SessionLinks } from "@/components/AppHeader";
import { DoseRow } from "@/components/DoseRow";
import { CONTROL, Field } from "@/components/Field";
import { Loading, Notice, Problem } from "@/components/Feedback";
import { Page, SplitView } from "@/components/Page";
import { Note, Panel } from "@/components/Panel";
import { PatientBanner } from "@/components/PatientBanner";
import { RecordCard, RecordRow } from "@/components/RecordCard";
import { StatusChip } from "@/components/StatusChip";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { useOnline } from "@/lib/online";
import { cn } from "@/lib/utils";
import { trustListStatuses } from "../server/links";
import { addDoses, readChart, readContext, readNewborns, readProducts, registerNewborn, type Product } from "./api";
import { WorkplaceGate } from "../Workplaces";
import { PrintCards, PrintStation, usePrintCards } from "./PrintStation";
import { HOSPITAL_NUMBER_SYSTEM, hospitalNumberOf, type Context, type Dose, type Newborn, type TimedPatient } from "./records";

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

type Header = (subtitle?: string) => ReactNode;

const GENDERS = ["male", "female", "other", "unknown"] as const;

export function Emr({ search, go }: Props) {
  return <WorkplaceGate configuration="hospital" requested={search.organization}>{(organization) => <Hospital organization={organization} search={search} go={go} />}</WorkplaceGate>;
}

function Hospital({ organization, search, go }: Props & { organization: string }) {
  const medplum = useMedplum();
  const profile = medplum.getProfile();
  const context = useQuery({
    queryKey: ["hospital-context", organization, profile?.id],
    queryFn: () => readContext(medplum, organization, profile?.id ?? ""),
  });
  const products = useQuery({ queryKey: ["products"], queryFn: () => readProducts(medplum) });

  if (context.isError) return <Failure error={context.error} />;
  if (!context.data) return <Loading />;
  const { hospital, practitioner, ...ids } = context.data;
  const header: Header = (subtitle) => <HospitalHeader hospital={hospital} practitioner={practitioner} subtitle={subtitle} />;

  if (search.patient && search.screen === "s1") {
    return (
      <DischargeSummary
        header={header}
        patientId={search.patient}
        onBack={() => go({ screen: "emr" })}
        onPrint={() => go({ screen: "print" })}
        onDone={(name) => go({ patient: "", screen: "emr", discharged: name })}
      />
    );
  }
  if (search.patient && search.screen === "print") {
    return <PrintStation header={header} patientId={search.patient} onBack={() => go({ screen: "s1" })} />;
  }
  if (search.patient) {
    return (
      <PatientChart
        header={header}
        patientId={search.patient}
        context={ids}
        products={products.data ?? []}
        onList={() => go({ patient: "" })}
        onSummary={() => go({ screen: "s1" })}
      />
    );
  }
  return (
    <Register
      header={header}
      hospitalId={organization}
      context={ids}
      products={products.data ?? []}
      discharged={search.discharged}
      onRegistered={(patient) => go({ patient, screen: "s1", discharged: "" })}
      onOpen={(patient) => go({ patient, discharged: "" })}
    />
  );
}

function HospitalHeader({ hospital, practitioner, subtitle }: { hospital: Organization; practitioner: Practitioner; subtitle?: string }) {
  const online = useOnline();
  const medplum = useMedplum();
  const lists = useQuery({ queryKey: ["trust-lists"], queryFn: () => trustListStatuses({ data: { token: medplum.getAccessToken() ?? "" } }) });
  const country = hospital.contact?.flatMap((contact) => contact.address?.country ?? [])[0];
  const participant = country && lists.data?.find((list) => list.name === "dev")?.participants.includes(country);
  return (
    <AppHeader
      icon={HospitalIcon}
      title={`${hospital.name} — Newborn Immunization Discharge`}
      subtitle={subtitle ?? `Practitioner: ${nameOf(practitioner)}`}
      chips={participant && <StatusChip tone="success">GDHCN DEV Participant: {country}</StatusChip>}
      status={<StatusChip tone={online ? "success" : "warning"}>{online ? "Online" : "Offline"}</StatusChip>}
      links={<SessionLinks />}
    />
  );
}

function Newborns({ hospitalId, onOpen }: { hospitalId: string; onOpen: (patientId: string) => void }) {
  const medplum = useMedplum();
  const newborns = useQuery({ queryKey: ["newborns", hospitalId], queryFn: () => readNewborns(medplum, hospitalId) });
  return (
    <RecordCard title="Newborns" icon={Baby} summary={newborns.data && <StatusChip tone="info">{newborns.data.length} registered</StatusChip>}>
      {newborns.isError && <Failure error={newborns.error} />}
      {newborns.data?.length === 0 && <li className="text-xs text-slate-500">No newborns registered yet.</li>}
      {newborns.data?.map((patient) => (
        <RecordRow key={patient.id} icon={Baby} label={nameOf(patient)} code={hospitalNumberOf(patient)} detail={`Born ${patient.birthDate}`} onOpen={() => onOpen(patient.id ?? "")} />
      ))}
    </RecordCard>
  );
}

interface RegisterProps {
  header: Header;
  hospitalId: string;
  context: Context;
  products: Product[];
  discharged: string;
  onRegistered: (patientId: string) => void;
  onOpen: (patientId: string) => void;
}

function Register({ header, hospitalId, context, products, discharged, onRegistered, onOpen }: RegisterProps) {
  const medplum = useMedplum();
  const queries = useQueryClient();
  const id = useId();
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
    <Page
      header={header()}
      onSubmit={submit}
      actions={
        <ActionBar caption={`Writes the newborn and ${rows.length} ${rows.length === 1 ? "Immunization" : "Immunizations"}`} notice={register.isError && <Failure error={register.error} />}>
          <Action tone="primary" type="submit" disabled={register.isPending}>
            Save Doses &amp; Open Discharge
          </Action>
        </ActionBar>
      }
    >
      <SplitView
        main={
          <>
            <Panel title="Newborn">
              <div className="grid gap-2.5 sm:grid-cols-2">
                <Field id={`${id}-given`} label="Given name">
                  <Input id={`${id}-given`} required value={given} onChange={(e) => setGiven(e.target.value)} className={CONTROL} />
                </Field>
                <Field id={`${id}-family`} label="Family name">
                  <Input id={`${id}-family`} required value={family} onChange={(e) => setFamily(e.target.value)} className={CONTROL} />
                </Field>
                <Field id={`${id}-sex`} label="Sex">
                  <NativeSelect id={`${id}-sex`} value={gender} onChange={(e) => setGender(e.target.value as Newborn["gender"])} className={CONTROL}>
                    {GENDERS.map((it) => <NativeSelectOption key={it} value={it}>{sentence(it)}</NativeSelectOption>)}
                  </NativeSelect>
                </Field>
                <Field id={`${id}-born`} label="Date of birth">
                  <Input id={`${id}-born`} required type="datetime-local" max={localNow()} value={birth} onChange={(e) => setBirth(e.target.value)} className={CONTROL} />
                </Field>
                <Field id={`${id}-number`} label="Hospital number">
                  <Input id={`${id}-number`} required placeholder="CRH-HN-YY-NNNNNN" value={hospitalNumber} onChange={(e) => setHospitalNumber(e.target.value)} className={cn(CONTROL, "font-mono")} />
                </Field>
              </div>
            </Panel>
            <DoseCards rows={rows} products={products} birth={birth} onChange={setDoses} />
          </>
        }
        side={
          <>
            {discharged && (
              <Notice tone="success" role="status" title="Discharged">
                {discharged}
              </Notice>
            )}
            <ProductCodeRule />
            <Newborns hospitalId={hospitalId} onOpen={onOpen} />
          </>
        }
      />
    </Page>
  );
}

interface ChartProps {
  header: Header;
  patientId: string;
  context: Context;
  products: Product[];
  onList: () => void;
  onSummary: () => void;
}

function PatientChart({ header, patientId, context, products, onList, onSummary }: ChartProps) {
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
  if (!chart.data) return <Loading />;
  const { patient, doses } = chart.data;
  const birth = birthLocal(patient);
  return (
    <Page
      header={header()}
      banner={<NewbornBanner patient={patient} />}
      actions={
        <ActionBar>
          <Action onClick={onList}>Back to the newborns</Action>
          <Action tone="primary" onClick={onSummary}>Open Discharge</Action>
        </ActionBar>
      }
    >
      <SplitView
        main={<Doses doses={doses} />}
        side={
          <form
            className="flex flex-col gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              add.mutate();
            }}
          >
            <DoseCards rows={rows} products={products} birth={birth} onChange={setRows} />
            {rows.length > 0 && (
              <Action tone="primary" type="submit" disabled={add.isPending} className="self-end">
                Save doses
              </Action>
            )}
            {add.isError && <Failure error={add.error} />}
          </form>
        }
      />
    </Page>
  );
}

interface SummaryProps {
  header: Header;
  patientId: string;
  onBack: () => void;
  onPrint: () => void;
  onDone: (name: string) => void;
}

function DischargeSummary({ header, patientId, onBack, onPrint, onDone }: SummaryProps) {
  const medplum = useMedplum();
  const chart = useQuery({ queryKey: ["chart", patientId], queryFn: () => readChart(medplum, patientId) });
  const print = usePrintCards(patientId);
  if (chart.isError) return <Failure error={chart.error} />;
  if (!chart.data) return <Loading />;
  const { patient, doses } = chart.data;
  const cards = print.data?.cards.length ?? 0;
  return (
    <Page
      header={header()}
      banner={<NewbornBanner patient={patient} />}
      actions={
        <ActionBar>
          <Action onClick={onBack}>Back</Action>
          {cards > 0 && (
            <Action onClick={onPrint}>
              <Printer aria-hidden />
              Print {cards} DVC {cards === 1 ? "Card" : "Cards"}
            </Action>
          )}
          <Action tone="primary" onClick={() => onDone(nameOf(patient))}>Complete Discharge</Action>
        </ActionBar>
      }
    >
      <SplitView
        wide="side"
        main={
          <>
            <Doses doses={doses} />
            <ProductCodeRule />
          </>
        }
        side={
          <>
            <Note icon={Printer}>Printable paper DVC wallet cards, carried by the family returning to Onesa</Note>
            {print.isError ? <Failure error={print.error} /> : print.data ? <PrintCards {...print.data} /> : <Loading />}
          </>
        }
      />
    </Page>
  );
}

function NewbornBanner({ patient }: { patient: TimedPatient }) {
  const hospitalNumber = hospitalNumberOf(patient);
  const onlyHospitalNumber = (patient.identifier ?? []).every((it) => it.system === HOSPITAL_NUMBER_SYSTEM);
  return (
    <PatientBanner
      name={nameOf(patient)}
      chips={
        <>
          {hospitalNumber && <StatusChip surface="dark" tone="warning">Hospital number: {hospitalNumber}</StatusChip>}
          {onlyHospitalNumber && <StatusChip surface="dark">No Taifafeki CID</StatusChip>}
        </>
      }
      subtitle={`Born: ${birthLocal(patient).replace("T", " ")} • Sex: ${sentence(patient.gender ?? "unknown")}`}
    />
  );
}

function Doses({ doses }: { doses: Immunization[] }) {
  const completed = doses.filter((dose) => dose.status === "completed").length;
  return (
    <RecordCard
      title="Administered Birth Vaccines"
      icon={Syringe}
      summary={<StatusChip tone="success">{completed} {completed === 1 ? "Dose" : "Doses"} Completed</StatusChip>}
    >
      {doses.length === 0 && <li className="text-xs text-slate-500">No doses recorded.</li>}
      {doses.map((dose) => <DoseRow key={dose.id} dose={dose} />)}
    </RecordCard>
  );
}

function ProductCodeRule() {
  return (
    <Notice title="Technical Implementation Rule:">
      WHO PreQual product codes are stored in the digital certificate as issued. Taifafeki does not translate codes into Onesa national schedule codes prior to departure.
    </Notice>
  );
}

function DoseCards({ rows, products, birth, onChange }: { rows: DoseDraft[]; products: Product[]; birth: string; onChange: (rows: DoseDraft[]) => void }) {
  const id = useId();
  const edit = (index: number, change: Partial<DoseDraft>) =>
    onChange(rows.map((row, at) => (at === index ? { ...row, ...change } : row)));
  return (
    <>
      {rows.map((row, index) => (
        <Panel
          key={index}
          title={products.find((it) => it.code === row.productCode)?.display ?? "Dose"}
          icon={Syringe}
          aside={
            <Action type="button" className="min-h-0 px-2 py-1 text-2xs" onClick={() => onChange(rows.filter((_, at) => at !== index))}>
              Remove
            </Action>
          }
        >
          <div className="grid gap-2.5 sm:grid-cols-2">
            <Field id={`${id}-${index}-product`} label="Product">
              <NativeSelect id={`${id}-${index}-product`} value={row.productCode} onChange={(e) => edit(index, { productCode: e.target.value })} className={CONTROL}>
                {products.map((it) => <NativeSelectOption key={it.code} value={it.code}>{it.display}</NativeSelectOption>)}
              </NativeSelect>
            </Field>
            <Field id={`${id}-${index}-lot`} label="Lot">
              <Input id={`${id}-${index}-lot`} required value={row.lot} onChange={(e) => edit(index, { lot: e.target.value })} className={cn(CONTROL, "font-mono")} />
            </Field>
            <Field id={`${id}-${index}-given`} label="Given">
              <Input id={`${id}-${index}-given`} required type="datetime-local" min={birth} max={localNow()} value={row.occurredAt} onChange={(e) => edit(index, { occurredAt: e.target.value })} className={CONTROL} />
            </Field>
          </div>
        </Panel>
      ))}
      <Action type="button" className="self-start" onClick={() => onChange([...rows, ...defaultDoses(products, birth).slice(0, 1)])}>
        <Plus aria-hidden />
        Add another dose
      </Action>
    </>
  );
}

function Failure({ error }: { error: unknown }) {
  return <Problem>{normalizeErrorString(error)}</Problem>;
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
  return name?.text ?? [name?.given?.join(" "), name?.family].filter(Boolean).join(" ");
}

function sentence(code: string) {
  return code.charAt(0).toUpperCase() + code.slice(1);
}

import { normalizeErrorString } from "@medplum/core";
import type { HumanName } from "@medplum/fhirtypes";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Camera, CameraOff, HousePlus } from "lucide-react";
import { useCallback, useId, useState, type ReactNode } from "react";
import { Action, ActionBar } from "@/components/ActionBar";
import { AppHeader } from "@/components/AppHeader";
import { Facts } from "@/components/Facts";
import { CONTROL, Field } from "@/components/Field";
import { Notice, Problem } from "@/components/Feedback";
import { Page, SplitView } from "@/components/Page";
import { Panel } from "@/components/Panel";
import { PasscodeCard } from "@/components/PasscodeCard";
import { ResultPanel } from "@/components/ResultPanel";
import { ScanFrame } from "@/components/ScanFrame";
import { StatusChip } from "@/components/StatusChip";
import { StatusFooter } from "@/components/StatusFooter";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useWorkplaces, WorkplaceGate } from "../Workplaces";
import { fetchSummary, scanVhl, trustListStatuses } from "../server/links";
import type { ListStatus } from "../server/trustLists";
import type { LinkOutcome } from "../server/viewer";
import { writeReceipt } from "./receipt";
import { Scanner } from "./Scanner";
import { SummaryScreen, type Receipt } from "./SummaryScreen";
import { openSummary, readSummary, type SummaryView } from "./summary";

const WHEN = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "shortOffset" });
const when = (epochSeconds: number) => WHEN.format(new Date(epochSeconds * 1000));

const LIST_NAMES: Record<ListStatus["name"], string> = { local: "Local test list", dev: "GDHCN DEV" };

interface Opened {
  view: SummaryView;
  receipt: Receipt;
  passcode: boolean;
}

export function Viewer({ organization }: { organization: string }) {
  return <WorkplaceGate configuration="clinic" requested={organization}>{(clinic) => <ViewerFor organization={clinic} />}</WorkplaceGate>;
}

function ViewerFor({ organization }: { organization: string }) {
  const medplum = useMedplum();
  const workplaces = useWorkplaces();
  const id = useId();
  const [camera, setCamera] = useState(false);
  const [pasted, setPasted] = useState("");
  const [passcode, setPasscode] = useState("");
  const [opening, setOpening] = useState(false);
  const [notice, setNotice] = useState<string>();
  const [opened, setOpened] = useState<Opened>();
  const token = () => medplum.getAccessToken() ?? "";
  const lists = useQuery({ queryKey: ["trust-lists"], queryFn: () => trustListStatuses({ data: { token: token() } }), refetchInterval: 60_000 });
  const scan = useMutation({
    mutationFn: (text: string) => scanVhl({ data: { token: token(), text } }),
    onMutate: () => {
      setPasscode("");
      setNotice(undefined);
    },
  });
  const { mutate } = scan;
  const found = scan.data?.kind === "fetch" ? scan.data : undefined;
  const failed = scan.data && scan.data.kind !== "fetch" ? scan.data : undefined;
  const scanned = useCallback(
    (text: string) => {
      setCamera(false);
      mutate(text);
    },
    [mutate],
  );

  const open = async (handle: string, needsPasscode: boolean) => {
    setOpening(true);
    setNotice(undefined);
    try {
      const outcome = await fetchSummary({ data: { token: token(), handle, passcode } });
      if (outcome.kind === "refused") return setNotice(outcome.message);
      const bundle = await openSummary(outcome.documents, outcome.key).catch(() => undefined);
      if (!bundle) return setNotice("The summary could not be opened");
      const view = readSummary(bundle);
      setOpened({ view, receipt: { state: "pending" }, passcode: needsPasscode });
      const practitionerId = medplum.getProfile()?.id ?? "";
      const receipt = await writeReceipt(medplum, { bundle, organizationId: organization, practitionerId, now: new Date() }).then(
        (): Receipt => ({ state: "recorded" }),
        (error: unknown): Receipt => ({ state: "failed", reason: normalizeErrorString(error) }),
      );
      setOpened((current) => (current?.view === view ? { ...current, receipt } : current));
    } catch (error) {
      setNotice(normalizeErrorString(error));
    } finally {
      setPasscode("");
      setOpening(false);
    }
  };

  const restart = () => {
    setOpened(undefined);
    setPasted("");
    setPasscode("");
    setNotice(undefined);
    scan.reset();
  };

  const clinic = workplaces.data?.find((it) => it.organization.id === organization)?.organization.name;
  const practitioner = nameOf(medplum.getProfile());
  const header = (chip: ReactNode) => (
    <AppHeader
      icon={HousePlus}
      title="Taifafeki Clinic Point-of-Care VHL Viewer (Country A)"
      subtitle={[practitioner && `Practitioner: ${practitioner}`, clinic].filter(Boolean).join(" • ")}
      chips={chip}
      links={<a href="/" className="hover:text-white">Change workplace</a>}
    />
  );

  if (opened) {
    return (
      <SummaryScreen
        header={header(<StatusChip tone="success">VHL Verified via Trust List</StatusChip>)}
        view={opened.view}
        receipt={opened.receipt}
        passcode={opened.passcode}
        onClose={restart}
      />
    );
  }

  return (
    <Page
      header={header(lists.data && trustChip(lists.data))}
      width={failed ? "narrow" : "wide"}
      actions={
        (found || failed) && (
          <ActionBar caption={found && "Requests the manifest from Onesa (ITI-YY5, signed)"}>
            <Action tone={failed ? "primary" : "secondary"} onClick={restart}>
              Scan Again
            </Action>
            {found && (
              <Action tone="primary" disabled={opening || (found.needsPasscode && !passcode)} onClick={() => void open(found.handle, found.needsPasscode)}>
                Open Records
              </Action>
            )}
          </ActionBar>
        )
      }
    >
      {failed ? (
        <NotVerified result={failed} />
      ) : (
        <SplitView
          main={
            notice ? (
              <ResultPanel tone="refused" title="Records Not Opened" facts={<Facts columns={1} items={[{ label: "Reason", value: <span role="alert">{notice}</span> }]} />} />
            ) : (
              <>
                <ScanFrame
                  label="Scan the QR on the family's phone"
                  action={
                    <button
                      type="button"
                      onClick={() => setCamera(!camera)}
                      className="inline-flex items-center gap-1 rounded-md bg-slate-800 px-2 py-0.5 font-sans font-semibold text-slate-100 hover:bg-slate-700 focus-visible:ring-[3px] focus-visible:ring-sky-400/50 focus-visible:outline-none"
                    >
                      {camera ? <CameraOff className="size-3.5" aria-hidden /> : <Camera className="size-3.5" aria-hidden />}
                      {camera ? "Stop camera" : "Scan with camera"}
                    </button>
                  }
                >
                  {camera && <Scanner onText={scanned} />}
                </ScanFrame>
                <Panel>
                  <Field id={`${id}-paste`} label="Or paste the link text">
                    <Textarea id={`${id}-paste`} value={pasted} onChange={(event) => setPasted(event.target.value)} rows={3} className={cn(CONTROL, "h-auto font-mono text-xs md:text-xs")} />
                  </Field>
                  <Action className="self-end" disabled={!pasted.trim() || scan.isPending} onClick={() => scanned(pasted)}>
                    Check link
                  </Action>
                </Panel>
                {scan.isError && <Problem>{normalizeErrorString(scan.error)}</Problem>}
              </>
            )
          }
          side={
            found ? (
              <>
                <ResultPanel
                  tone="pass"
                  title="Link Signature Valid"
                  verdict="PASS"
                  facts={<Facts items={[{ label: "Link for", value: found.label ?? "Health summary" }, { label: "Link to", value: hostOf(found.url), mono: true }, { label: "Valid until", value: when(found.exp) }]} />}
                >
                  Checked offline. The QR holds only a link and a key.
                </ResultPanel>
                {found.needsPasscode && <PasscodeCard value={passcode} onChange={setPasscode} />}
              </>
            ) : (
              <Notice tone="info" title="Waiting for a link">
                Scan the family's QR code, or paste the link text. It is checked here, against the trust lists, before anything is asked of Onesa.
              </Notice>
            )
          }
        />
      )}
      {lists.isError ? (
        <Problem>{normalizeErrorString(lists.error)}</Problem>
      ) : (
        lists.data && lists.data.length > 0 && <StatusFooter title="Trust lists" lines={lists.data.map(statusLine)} />
      )}
    </Page>
  );
}

function NotVerified({ result }: { result: Exclude<LinkOutcome, { kind: "fetch" }> }) {
  const facts =
    result.kind === "notVerified"
      ? [{ label: "Failed at step", value: result.step, mono: true }, { label: "Reason", value: result.reason, mono: true }]
      : [{ label: "Reason", value: "expired", mono: true }, { label: "Ended", value: when(result.exp) }];
  return (
    <div role="alert" className="flex flex-col gap-3">
      <ResultPanel tone="fail" title="Link Not Verified" verdict="FAIL" facts={<Facts items={facts} />}>
        Treat it as a paper record; don't open the link.
      </ResultPanel>
      {result.kind === "notVerified" && (
        <Notice tone="info" title="Next">
          Check that the trust lists below are current, then scan again.
        </Notice>
      )}
    </div>
  );
}

function trustChip(lists: ListStatus[]): ReactNode {
  if (lists.length === 0) return undefined;
  if (lists.some((list) => !list.fetchedAt)) return <StatusChip tone="danger">Trust List Not Loaded</StatusChip>;
  if (lists.some((list) => list.stale)) return <StatusChip tone="warning">Trust List Stale</StatusChip>;
  return <StatusChip tone="success">Trust Lists Loaded</StatusChip>;
}

function statusLine(list: ListStatus) {
  return {
    label: LIST_NAMES[list.name],
    detail: list.fetchedAt ? `fetched ${when(list.fetchedAt)}, ${list.entryCount} entries` : "not loaded",
    problems: [
      ...(list.stale ? ["Stale: older than 24 hours."] : []),
      ...(list.lastFailure ? [`Last failure ${when(list.lastFailure.at)}: ${list.lastFailure.reason}`] : []),
    ],
  };
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

function nameOf(person: { name?: HumanName[] } | undefined): string {
  const name = person?.name?.[0];
  return name?.text ?? [name?.given?.join(" "), name?.family].filter(Boolean).join(" ");
}

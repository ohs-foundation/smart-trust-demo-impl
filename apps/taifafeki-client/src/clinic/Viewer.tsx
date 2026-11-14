import { normalizeErrorString } from "@medplum/core";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { WorkplaceGate } from "../Workplaces";
import { fetchSummary, scanVhl, trustListStatuses } from "../server/links";
import { writeReceipt } from "./receipt";
import { Scanner } from "./Scanner";
import { SummaryScreen, type Receipt } from "./SummaryScreen";
import { openSummary, readSummary, type SummaryView } from "./summary";

const when = (epochSeconds: number) => new Date(epochSeconds * 1000).toISOString().replace("T", " ").slice(0, 16) + " UTC";

interface Opened {
  view: SummaryView;
  receipt: Receipt;
}

export function Viewer({ organization }: { organization: string }) {
  return <WorkplaceGate configuration="clinic" requested={organization}>{(clinic) => (
    <main>
      <h1>VHL Viewer (J4.1)</h1>
      <ViewerFor organization={clinic} />
    </main>
  )}</WorkplaceGate>;
}

function ViewerFor({ organization }: { organization: string }) {
  const medplum = useMedplum();
  const [camera, setCamera] = useState(false);
  const [pasted, setPasted] = useState("");
  const [passcode, setPasscode] = useState("");
  const [opening, setOpening] = useState(false);
  const [notice, setNotice] = useState<string>();
  const [opened, setOpened] = useState<Opened>();
  const token = () => medplum.getAccessToken() ?? "";
  const scan = useMutation({
    mutationFn: (text: string) => scanVhl({ data: { token: token(), text } }),
    onMutate: () => {
      setPasscode("");
      setNotice(undefined);
    },
  });
  const { mutate } = scan;
  const found = scan.data?.kind === "fetch" ? scan.data : undefined;
  const scanned = useCallback(
    (text: string) => {
      setCamera(false);
      mutate(text);
    },
    [mutate],
  );

  const open = async (handle: string) => {
    setOpening(true);
    setNotice(undefined);
    try {
      const outcome = await fetchSummary({ data: { token: token(), handle, passcode } });
      if (outcome.kind === "refused") return setNotice(outcome.message);
      const bundle = await openSummary(outcome.documents, outcome.key).catch(() => undefined);
      if (!bundle) return setNotice("The summary could not be opened");
      const view = readSummary(bundle);
      setOpened({ view, receipt: { state: "pending" } });
      const practitionerId = medplum.getProfile()?.id ?? "";
      const receipt = await writeReceipt(medplum, { bundle, organizationId: organization, practitionerId, now: new Date() }).then(
        (): Receipt => ({ state: "recorded" }),
        (error: unknown): Receipt => ({ state: "failed", reason: normalizeErrorString(error) }),
      );
      setOpened((current) => (current?.view === view ? { view, receipt } : current));
    } catch (error) {
      setNotice(normalizeErrorString(error));
    } finally {
      setPasscode("");
      setOpening(false);
    }
  };

  const close = () => {
    setOpened(undefined);
    setPasted("");
    scan.reset();
  };

  if (opened) return <SummaryScreen view={opened.view} receipt={opened.receipt} onClose={close} />;

  return (
    <section>
      <p>
        <button onClick={() => setCamera(!camera)}>{camera ? "Stop camera" : "Scan with camera"}</button>
      </p>
      {camera && <Scanner onText={scanned} />}
      <p>
        <label>
          Or paste the link text <br />
          <textarea value={pasted} onChange={(event) => setPasted(event.target.value)} rows={4} cols={60} />
        </label>
      </p>
      <p>
        <button disabled={!pasted.trim() || scan.isPending} onClick={() => scanned(pasted)}>Check link</button>
      </p>
      {scan.isError && <p role="alert">{normalizeErrorString(scan.error)}</p>}
      {scan.data?.kind === "notVerified" && (
        <div role="alert">
          <h2>Link not verified</h2>
          <p>Step: {scan.data.step}. Reason: {scan.data.reason}.</p>
          <p>Treat this card as paper.</p>
        </div>
      )}
      {scan.data?.kind === "expired" && (
        <div role="alert">
          <h2>Link expired</h2>
          <p>It ended on {when(scan.data.exp)}. Treat this card as paper.</p>
        </div>
      )}
      {found && (
        <div>
          <h2>Link verified</h2>
          <p>{found.label ?? "Health summary"}, valid until {when(found.exp)}.</p>
          {found.needsPasscode && (
            <p>
              <label>
                Passcode <input value={passcode} onChange={(event) => setPasscode(event.target.value)} inputMode="numeric" autoComplete="off" />
              </label>
            </p>
          )}
          <p>
            <button disabled={opening || (found.needsPasscode && !passcode)} onClick={() => void open(found.handle)}>Open summary</button>
          </p>
          {notice && <p role="alert">{notice}</p>}
        </div>
      )}
      <TrustLists token={token} />
    </section>
  );
}

function TrustLists({ token }: { token: () => string }) {
  const lists = useQuery({ queryKey: ["trust-lists"], queryFn: () => trustListStatuses({ data: { token: token() } }), refetchInterval: 60_000 });
  if (lists.isError) return <p role="alert">{normalizeErrorString(lists.error)}</p>;
  return (
    <section>
      <h2>Trust lists</h2>
      <ul>
        {lists.data?.map((list) => (
          <li key={list.name}>
            {list.name}: {list.fetchedAt ? `fetched ${when(list.fetchedAt)}, ${list.entryCount} entries` : "not loaded"}
            {list.stale && <span role="alert"> Stale: older than 24 hours.</span>}
            {list.lastFailure && <span role="alert"> Last failure {when(list.lastFailure.at)}: {list.lastFailure.reason}</span>}
          </li>
        ))}
      </ul>
    </section>
  );
}

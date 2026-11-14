import { normalizeErrorString } from "@medplum/core";
import { useMedplum } from "@medplum/react-hooks";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { scanVhl, trustListStatuses } from "../server/links";
import { Scanner } from "./Scanner";

const when = (epochSeconds: number) => new Date(epochSeconds * 1000).toISOString().replace("T", " ").slice(0, 16) + " UTC";

export function Viewer() {
  const medplum = useMedplum();
  const [camera, setCamera] = useState(false);
  const [pasted, setPasted] = useState("");
  const [passcode, setPasscode] = useState("");
  const token = () => medplum.getAccessToken() ?? "";
  const scan = useMutation({
    mutationFn: (text: string) => scanVhl({ data: { token: token(), text } }),
    onMutate: () => setPasscode(""),
  });
  const { mutate } = scan;
  const scanned = useCallback(
    (text: string) => {
      setCamera(false);
      mutate(text);
    },
    [mutate],
  );

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
      {scan.data?.kind === "fetch" && (
        <div>
          <h2>Link verified</h2>
          <p>{scan.data.label ?? "Health summary"}, valid until {when(scan.data.exp)}.</p>
          {scan.data.needsPasscode && (
            <p>
              <label>
                Passcode <input value={passcode} onChange={(event) => setPasscode(event.target.value)} inputMode="numeric" autoComplete="off" />
              </label>
            </p>
          )}
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

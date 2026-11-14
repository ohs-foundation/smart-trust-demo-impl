import { ChevronRight, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Panel } from "./Panel";

interface Props {
  title: string;
  icon?: LucideIcon;
  summary?: ReactNode;
  footnote?: ReactNode;
  children: ReactNode;
}

export function RecordCard({ title, icon, summary, footnote, children }: Props) {
  return (
    <Panel title={title} icon={icon} aside={summary}>
      <ul className="flex flex-col gap-1.5">{children}</ul>
      {footnote && <p className="text-2xs text-slate-500">{footnote}</p>}
    </Panel>
  );
}

interface RowProps {
  icon?: LucideIcon;
  label: ReactNode;
  code?: ReactNode;
  detail?: ReactNode;
  status?: ReactNode;
  onOpen?: () => void;
}

export function RecordRow({ icon: Icon, label, code, detail, status, onOpen }: RowProps) {
  const body = (
    <>
      {Icon && (
        <span className="grid size-[1.875rem] shrink-0 place-items-center rounded-lg border border-slate-200 bg-surface text-brand-taifa">
          <Icon className="size-4" aria-hidden />
        </span>
      )}
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="flex items-start justify-between gap-2">
          <b className="text-ui">{label}</b>
          {status && <span className="max-w-[60%] text-right">{status}</span>}
        </span>
        {code && <code className="font-mono text-2xs break-all text-slate-600">{code}</code>}
        {detail && <small className="text-2xs text-slate-500">{detail}</small>}
      </span>
      {onOpen && <ChevronRight className="size-4 shrink-0 self-center text-slate-400" aria-hidden />}
    </>
  );
  const row = "flex w-full items-start gap-2.5 rounded-[9px] border border-slate-200 bg-slate-50 px-2.5 py-2 text-left";
  return (
    <li>
      {onOpen ? (
        <button type="button" onClick={onOpen} className={`${row} transition-colors hover:border-brand-taifa/40 hover:bg-taifa-soft focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none`}>
          {body}
        </button>
      ) : (
        <div className={row}>{body}</div>
      )}
    </li>
  );
}

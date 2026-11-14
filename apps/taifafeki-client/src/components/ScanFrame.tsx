import { ScanLine } from "lucide-react";
import type { ReactNode } from "react";

interface Props {
  label: string;
  caption?: string;
  action?: ReactNode;
  children?: ReactNode;
}

const CORNER = "absolute size-3.5 border-emerald-400";

export function ScanFrame({ label, caption, action, children }: Props) {
  return (
    <section className="flex flex-col items-center gap-2.5 rounded-xl border-2 border-slate-800 bg-slate-950 px-3 pt-5 pb-2.5 text-slate-300">
      <div className="relative aspect-square w-56 max-w-[70%] rounded-control border-2 border-emerald-500 p-2">
        <i className={`${CORNER} -top-[5px] -left-[5px] border-t-3 border-l-3`} />
        <i className={`${CORNER} -top-[5px] -right-[5px] border-t-3 border-r-3`} />
        <i className={`${CORNER} -bottom-[5px] -left-[5px] border-b-3 border-l-3`} />
        <i className={`${CORNER} -right-[5px] -bottom-[5px] border-r-3 border-b-3`} />
        {children}
      </div>
      <div className="flex w-full flex-wrap items-center gap-1.5 rounded-md bg-slate-900/90 px-2 py-1 font-mono text-2xs">
        <ScanLine className="size-3.5 text-sky-400" aria-hidden />
        <span className="min-w-0 flex-1">{label}</span>
        {action}
      </div>
      {caption && <p className="text-2xs text-slate-400">{caption}</p>}
    </section>
  );
}

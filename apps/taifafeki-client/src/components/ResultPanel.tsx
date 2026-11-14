import { ShieldCheck, ShieldX, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

const TONES = {
  pass: { icon: ShieldCheck, frame: "border-ok-line bg-ok-soft text-ok-ink", mark: "text-emerald-600", inner: "border-ok-line" },
  fail: { icon: ShieldX, frame: "border-bad-line bg-bad-soft text-bad-ink", mark: "", inner: "border-bad-line" },
  refused: { icon: TriangleAlert, frame: "border-warn-line bg-warn-soft text-warn", mark: "", inner: "border-warn-line" },
};

interface Props {
  tone: keyof typeof TONES;
  title: string;
  verdict?: string;
  facts?: ReactNode;
  children?: ReactNode;
}

export function ResultPanel({ tone, title, verdict, facts, children }: Props) {
  const { icon: Icon, frame, mark, inner } = TONES[tone];
  return (
    <section className={cn("flex flex-col gap-2 rounded-xl border-2 px-3.5 py-3", frame)}>
      <div className="flex items-center gap-2">
        <Icon className={cn("size-6 shrink-0", mark)} aria-hidden />
        <h2 className="flex-1 text-ui font-bold">{title}</h2>
        {verdict && <span className={cn("rounded-md border bg-surface px-2 font-mono text-3xs leading-normal font-bold", inner)}>{verdict}</span>}
      </div>
      {facts}
      {children && <div className={cn("rounded-lg border bg-surface/70 px-2.5 py-2 text-xs text-slate-700", inner)}>{children}</div>}
    </section>
  );
}

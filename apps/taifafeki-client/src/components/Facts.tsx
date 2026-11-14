import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

interface Fact {
  label: string;
  value: ReactNode;
  mono?: boolean;
}

export function Facts({ items, columns = 2 }: { items: Fact[]; columns?: 1 | 2 }) {
  return (
    <dl className={cn("grid gap-x-3 gap-y-1.5", columns === 2 && "grid-cols-2")}>
      {items.map((item) => (
        <div key={item.label} className="min-w-0">
          <dt className="font-mono text-3xs tracking-wide text-slate-400 uppercase">{item.label}</dt>
          <dd className={cn("text-xs font-semibold break-words text-slate-800", item.mono && "font-mono")}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

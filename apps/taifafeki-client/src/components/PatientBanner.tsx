import { Baby } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { CONTAINER } from "./Page";

interface Props {
  name: string;
  chips?: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
  surface?: "dark" | "light";
}

export function PatientBanner({ name, chips, subtitle, children, surface = "dark" }: Props) {
  const dark = surface === "dark";
  const person = (
    <div className={cn("flex items-start gap-2.5", dark && cn(CONTAINER, "items-center py-2.5"))}>
      <span className="grid size-8 shrink-0 place-items-center rounded-full bg-newborn text-white shadow-xs">
        <Baby className="size-5" aria-hidden />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <b className="text-sm">{name}</b>
          {chips}
        </div>
        {subtitle && <small className={cn("text-2xs", dark ? "text-slate-400" : "text-slate-500")}>{subtitle}</small>}
        {children}
      </div>
    </div>
  );
  return dark ? (
    <section className="border-b border-slate-800 bg-slate-900 text-white print:hidden">{person}</section>
  ) : (
    <section className="rounded-xl border border-line bg-surface px-3.5 py-3 shadow-xs">{person}</section>
  );
}

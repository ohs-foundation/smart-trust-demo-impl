import { HospitalIcon, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { CONTAINER } from "./Page";

interface Props {
  icon: LucideIcon;
  title: string;
  subtitle?: string;
  chips?: ReactNode;
  status?: ReactNode;
  links?: ReactNode;
}

export function AppHeader({ icon: Icon, title, subtitle, chips, status, links }: Props) {
  return (
    <div className="print:hidden">
      <div className="bg-frame text-slate-300">
        <div className={cn(CONTAINER, "flex items-center justify-between gap-4 py-2")}>
          <span className="text-ui font-semibold text-white">Taifafeki Facility Client</span>
          {links && <nav className="flex items-center gap-4 font-mono text-xs">{links}</nav>}
        </div>
      </div>
      <header className="border-b border-line bg-surface">
        <div className={cn(CONTAINER, "flex items-center gap-3 py-3")}>
          <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-brand-taifa/30 bg-brand-taifa/12 text-brand-taifa">
            <Icon className="size-4" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <h1 className="text-base leading-snug font-bold">{title}</h1>
            {subtitle && <p className="text-xs text-muted">{subtitle}</p>}
            {chips && <div className="mt-1 flex flex-wrap gap-1.5">{chips}</div>}
          </div>
          {status && <div className="max-w-[45%] shrink-0">{status}</div>}
        </div>
      </header>
    </div>
  );
}

export function ClientHeader() {
  return <AppHeader icon={HospitalIcon} title="Taifafeki Facility Client" subtitle="Taifafeki Ministry of Public Health • Medplum" />;
}

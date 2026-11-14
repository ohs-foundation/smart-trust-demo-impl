import type { ReactNode } from "react";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

interface Props {
  id: string;
  label: string;
  children: ReactNode;
  className?: string;
}

export function Field({ id, label, children, className }: Props) {
  return (
    <div className={cn("grid min-w-0 content-start gap-1 *:data-[slot=native-select-wrapper]:w-full", className)}>
      <Label htmlFor={id} className="font-mono text-3xs font-medium tracking-wide text-slate-500 uppercase">
        {label}
      </Label>
      {children}
    </div>
  );
}

export const CONTROL = "h-10 rounded-control border-slate-300 bg-surface px-2.5 text-sm shadow-none md:text-sm";

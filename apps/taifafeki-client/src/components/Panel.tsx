import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

interface Props {
  title?: string;
  icon?: LucideIcon;
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function Panel({ title, icon: Icon, aside, children, className }: Props) {
  return (
    <Card className={cn("gap-2 rounded-xl px-3.5 py-3 shadow-xs", className)}>
      {title && (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="flex items-center gap-1.5 text-ui font-bold">
            {Icon && <Icon className="size-4 text-brand-taifa" aria-hidden />}
            {title}
          </h2>
          {aside}
        </div>
      )}
      {children}
    </Card>
  );
}

export function Note({ icon: Icon, children }: { icon: LucideIcon; children: ReactNode }) {
  return (
    <p className="flex items-start gap-1.5 text-2xs text-slate-500">
      <Icon className="mt-px size-3.5 shrink-0" aria-hidden />
      {children}
    </p>
  );
}

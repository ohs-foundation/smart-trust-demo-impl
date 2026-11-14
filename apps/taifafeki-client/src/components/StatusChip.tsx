import { cva, type VariantProps } from "class-variance-authority";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

const chip = cva("h-auto w-auto rounded-full px-2 py-0 font-mono text-2xs font-medium whitespace-normal", {
  variants: {
    tone: {
      neutral: "border-slate-200 bg-slate-100 text-slate-700",
      info: "border-info-line bg-info-soft text-info-ink",
      success: "border-ok-line bg-ok-soft text-ok-ink",
      warning: "border-warn-line bg-warn-soft text-warn",
      danger: "border-bad-line bg-bad-soft text-bad-ink",
    },
    surface: { light: "", dark: "" },
  },
  compoundVariants: [
    { surface: "dark", tone: "neutral", className: "border-slate-700 bg-slate-800 text-slate-200" },
    { surface: "dark", tone: "info", className: "border-info-ink/60 bg-info-ink/35 text-info-line" },
    { surface: "dark", tone: "success", className: "border-ok-ink/60 bg-ok-ink/35 text-ok-line" },
    { surface: "dark", tone: "warning", className: "border-warn/60 bg-warn/35 text-warn-line" },
    { surface: "dark", tone: "danger", className: "border-bad-ink/60 bg-bad-ink/35 text-bad-line" },
  ],
  defaultVariants: { tone: "neutral", surface: "light" },
});

interface Props extends VariantProps<typeof chip> {
  icon?: LucideIcon;
  children: ReactNode;
  className?: string;
}

export function StatusChip({ tone, surface, icon: Icon, children, className }: Props) {
  return (
    <Badge variant="outline" className={cn(chip({ tone, surface }), className)}>
      {Icon && <Icon aria-hidden />}
      {children}
    </Badge>
  );
}

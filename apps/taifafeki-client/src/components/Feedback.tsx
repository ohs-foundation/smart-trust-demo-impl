import { Shield, ShieldCheck, TriangleAlert } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { cn } from "@/lib/utils";

const NOTICE = {
  warning: { icon: TriangleAlert, frame: "border-warn-line bg-warn-soft text-warn" },
  info: { icon: Shield, frame: "border-info-line bg-info-soft text-info-ink" },
  success: { icon: ShieldCheck, frame: "border-ok-line bg-ok-soft text-ok-ink" },
};

type NoticeProps = Omit<ComponentProps<typeof Alert>, "title"> & { tone?: keyof typeof NOTICE; title?: ReactNode };

export function Notice({ tone = "warning", title, children, className, ...props }: NoticeProps) {
  const { icon: Icon, frame } = NOTICE[tone];
  return (
    <Alert
      role="note"
      className={cn("gap-y-0.5 rounded-xl border-2 px-3 py-2.5 has-[>svg]:grid-cols-[1.25rem_1fr] has-[>svg]:gap-x-2.5 [&>svg]:size-5 [&>svg]:translate-y-0", frame, className)}
      {...props}
    >
      <Icon aria-hidden />
      {title && <AlertTitle className="line-clamp-none text-ui font-bold">{title}</AlertTitle>}
      <AlertDescription className="text-xs text-current">{children}</AlertDescription>
    </Alert>
  );
}

export function Problem({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-lg border border-bad-line bg-bad-soft px-3 py-2 text-xs font-medium text-bad-ink">
      {children}
    </p>
  );
}

export function Loading({ children = "Loading" }: { children?: ReactNode }) {
  return <p className="p-6 text-center text-xs text-slate-500">{children}</p>;
}

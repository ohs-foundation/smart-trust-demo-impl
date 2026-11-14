import type { FormEvent, ReactNode } from "react";
import { cn } from "@/lib/utils";

export const CONTAINER = "mx-auto w-full max-w-6xl px-4 group-data-[width=narrow]/page:max-w-3xl";

interface Props {
  header: ReactNode;
  banner?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  onSubmit?: (event: FormEvent<HTMLFormElement>) => void;
  width?: "wide" | "narrow";
}

const FRAME = "group/page flex min-h-dvh flex-col";

export function Page({ header, banner, actions, children, onSubmit, width = "wide" }: Props) {
  const content = (
    <>
      {header}
      {banner}
      <main className={cn(CONTAINER, "flex flex-1 flex-col gap-3 px-3 py-3 md:px-4 md:py-4")}>{children}</main>
      {actions}
    </>
  );
  return onSubmit ? (
    <form className={FRAME} data-width={width} onSubmit={onSubmit}>
      {content}
    </form>
  ) : (
    <div className={FRAME} data-width={width}>
      {content}
    </div>
  );
}

interface SplitProps {
  main: ReactNode;
  side?: ReactNode;
  wide?: "main" | "side";
}

export function SplitView({ main, side, wide = "main" }: SplitProps) {
  return (
    <div className={cn("grid items-start gap-3", side && (wide === "main" ? "md:grid-cols-[7fr_5fr]" : "md:grid-cols-[5fr_7fr]"))}>
      <div className="flex min-w-0 flex-col gap-3">{main}</div>
      {side && <div className="flex min-w-0 flex-col gap-3">{side}</div>}
    </div>
  );
}

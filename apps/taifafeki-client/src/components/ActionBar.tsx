import type { ComponentProps, ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { CONTAINER } from "./Page";

interface Props {
  children: ReactNode;
  caption?: string;
  notice?: ReactNode;
}

export function ActionBar({ children, caption, notice }: Props) {
  return (
    <footer className="sticky bottom-0 border-t border-line bg-surface print:hidden">
      <div className={cn(CONTAINER, "flex flex-col gap-1.5 py-2.5")}>
        {notice}
        <div className="flex flex-wrap justify-end gap-2">{children}</div>
        {caption && <p className="text-center font-mono text-2xs text-slate-500">{caption}</p>}
      </div>
    </footer>
  );
}

type ActionProps = Omit<ComponentProps<typeof Button>, "variant" | "size"> & { tone?: "primary" | "secondary" };

export function Action({ tone = "secondary", className, ...props }: ActionProps) {
  return (
    <Button
      variant={tone === "primary" ? "default" : "outline"}
      className={cn(
        "h-auto min-h-9 rounded-control px-3.5 py-2 text-ui font-bold whitespace-normal",
        tone === "primary" ? "hover:bg-warn" : "border-slate-300 bg-surface text-slate-700 shadow-none",
        className,
      )}
      {...props}
    />
  );
}

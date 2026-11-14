import { Eye, EyeOff, Lock } from "lucide-react";
import { useState } from "react";
import { cn } from "@/lib/utils";
import { Panel } from "./Panel";

interface Props {
  value: string;
  onChange: (value: string) => void;
}

export function PasscodeCard({ value, onChange }: Props) {
  const [shown, setShown] = useState(false);
  const [focused, setFocused] = useState(false);
  const cells = Math.max(4, value.length);
  const toggle = (
    <button
      type="button"
      aria-pressed={shown}
      onClick={() => setShown(!shown)}
      className="inline-flex items-center gap-1 rounded-md px-1 py-0.5 text-2xs font-medium text-slate-500 hover:text-slate-700 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      {shown ? <Eye className="size-3.5" aria-hidden /> : <EyeOff className="size-3.5" aria-hidden />}
      {shown ? "Shown" : "Hidden"}
    </button>
  );
  return (
    <Panel title="Passcode the family says aloud" icon={Lock} aside={toggle}>
      <div className="relative mx-auto flex flex-wrap justify-center gap-2 py-1">
        {Array.from({ length: cells }, (_, at) => (
          <span
            key={at}
            aria-hidden
            className={cn(
              "grid h-13 w-11 place-items-center rounded-control border border-slate-200 bg-slate-100 font-mono text-[1.375rem] font-bold",
              !shown && "text-[1.625rem] text-slate-500",
              focused && at === Math.min(value.length, cells - 1) && "border-brand-taifa ring-[3px] ring-ring/30",
            )}
          >
            {value[at] && (shown ? value[at] : "•")}
          </span>
        ))}
        <input
          aria-label="Passcode"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          inputMode="numeric"
          autoComplete="off"
          className="absolute inset-0 size-full cursor-text opacity-0"
        />
      </div>
      <p className="text-2xs text-slate-500">Sent to Onesa with the request, never stored here.</p>
    </Panel>
  );
}

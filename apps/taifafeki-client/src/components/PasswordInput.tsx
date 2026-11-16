import { EyeIcon, EyeOffIcon } from "lucide-react";
import { useState } from "react";
import { cn } from "@/lib/utils";
import { CONTROL } from "./Field";
import { Input } from "./ui/input";

export function PasswordInput({ id }: { id: string }) {
  const [shown, setShown] = useState(false);
  const Icon = shown ? EyeOffIcon : EyeIcon;
  return (
    <div className="relative">
      <Input id={id} name="password" type={shown ? "text" : "password"} autoComplete="current-password" required className={cn(CONTROL, "pr-10")} />
      <button
        type="button"
        aria-label={shown ? "Hide password" : "Show password"}
        aria-pressed={shown}
        onClick={() => setShown((current) => !current)}
        className="absolute inset-y-0 right-0 grid w-10 place-items-center text-slate-500 hover:text-slate-800 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <Icon className="size-4" aria-hidden />
      </button>
    </div>
  );
}

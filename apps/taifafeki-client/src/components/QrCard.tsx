import type { ReactNode } from "react";
import { Card } from "@/components/ui/card";
import { QrCode } from "./QrCode";

interface Props {
  text: string;
  holder: string;
  holderNote?: string;
  label: string;
  badge?: ReactNode;
  caption?: string;
  alt: string;
}

export function QrCard({ text, holder, holderNote, label, badge, caption, alt }: Props) {
  return (
    <Card className="items-center gap-2 rounded-xl px-3.5 py-3 text-center shadow-xs print:break-after-page print:shadow-none">
      <div className="self-stretch border-b border-line pb-1.5 text-2xs font-bold tracking-wider text-slate-800 uppercase">Digital Vaccine Certificate</div>
      <div className="flex flex-col self-stretch text-left">
        <b className="text-ui">{holder}</b>
        {holderNote && <small className="text-2xs text-muted">{holderNote}</small>}
      </div>
      <div className="self-start rounded-md bg-taifa-soft px-2 py-0.5 text-xs font-semibold text-amber-900">{label}</div>
      <QrCode text={text} alt={alt} />
      {badge}
      {caption && <p className="text-2xs text-slate-500">{caption}</p>}
    </Card>
  );
}

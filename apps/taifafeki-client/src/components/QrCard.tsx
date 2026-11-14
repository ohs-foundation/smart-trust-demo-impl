import { useQuery } from "@tanstack/react-query";
import QRCode from "qrcode";
import type { ReactNode } from "react";
import { Card } from "@/components/ui/card";

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
  const qr = useQuery({ queryKey: ["qr", text], queryFn: () => QRCode.toDataURL(text, { margin: 2, width: 320 }) });
  return (
    <Card className="items-center gap-2 rounded-xl px-3.5 py-3 text-center shadow-xs print:break-after-page print:shadow-none">
      <div className="self-stretch border-b border-line pb-1.5 text-2xs font-bold tracking-wider text-slate-800 uppercase">Digital Vaccine Certificate</div>
      <div className="flex flex-col self-stretch text-left">
        <b className="text-ui">{holder}</b>
        {holderNote && <small className="text-2xs text-muted">{holderNote}</small>}
      </div>
      <div className="self-start rounded-md bg-taifa-soft px-2 py-0.5 text-xs font-semibold text-amber-900">{label}</div>
      <div className="w-full max-w-44 rounded-control border border-line bg-surface p-1.5">
        {qr.data && <img src={qr.data} alt={alt} width={320} height={320} className="block h-auto w-full" />}
      </div>
      {badge}
      {caption && <p className="text-2xs text-slate-500">{caption}</p>}
    </Card>
  );
}

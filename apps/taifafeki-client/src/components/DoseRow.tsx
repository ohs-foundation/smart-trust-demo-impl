import type { Immunization } from "@medplum/fhirtypes";
import { Syringe } from "lucide-react";
import { PRODUCT_SYSTEM } from "@/hospital/records";
import { RecordRow } from "./RecordCard";
import { StatusChip } from "./StatusChip";

export function DoseRow({ dose }: { dose: Immunization }) {
  const codings = dose.vaccineCode?.coding ?? [];
  const product = codings.find((it) => it.system === PRODUCT_SYSTEM) ?? codings[0];
  const cvx = codings.find((it) => it.system?.endsWith("/cvx"))?.code;
  const number = dose.protocolApplied?.[0]?.doseNumberPositiveInt;
  return (
    <RecordRow
      icon={Syringe}
      label={product?.display ?? product?.code}
      code={[product?.code, cvx && `CVX ${cvx}`].filter(Boolean).join(" • ")}
      detail={[dose.lotNumber && `Lot ${dose.lotNumber}`, dose.occurrenceDateTime?.slice(0, 16).replace("T", " "), number && `Dose ${number}`].filter(Boolean).join(" • ")}
      status={<StatusChip tone={dose.status === "completed" ? "success" : "neutral"}>{sentence(dose.status)}</StatusChip>}
    />
  );
}

function sentence(code: string) {
  return code.charAt(0).toUpperCase() + code.slice(1).replaceAll("-", " ");
}

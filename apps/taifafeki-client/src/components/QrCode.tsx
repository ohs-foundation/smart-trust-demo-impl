import QRCode from "qrcode";
import { useMemo } from "react";

const QUIET_ZONE = 4;

interface Props {
  text: string;
  alt: string;
  /** Any CSS length; the code is always square, at least 280px. */
  size?: string;
}

export function QrCode({ text, alt, size = "18rem" }: Props) {
  const { count, path } = useMemo(() => {
    const { size: modules, data } = QRCode.create(text).modules;
    const cells: string[] = [];
    data.forEach((dark, at) => {
      if (dark) cells.push(`M${(at % modules) + QUIET_ZONE} ${Math.floor(at / modules) + QUIET_ZONE}h1v1h-1z`);
    });
    return { count: modules + 2 * QUIET_ZONE, path: cells.join("") };
  }, [text]);
  return (
    <svg
      role="img"
      aria-label={alt}
      viewBox={`0 0 ${count} ${count}`}
      shapeRendering="crispEdges"
      className="block aspect-square min-w-70 bg-white"
      style={{ width: size }}
    >
      <rect width={count} height={count} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}

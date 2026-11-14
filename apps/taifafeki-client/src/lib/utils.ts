import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

const twMerge = extendTailwindMerge({ extend: { theme: { text: ["3xs", "2xs", "ui"], radius: ["control"] } } });

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

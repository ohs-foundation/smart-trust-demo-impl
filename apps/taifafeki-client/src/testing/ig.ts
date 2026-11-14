import { readFileSync } from "node:fs";
import { join } from "node:path";

interface Element {
  id: string;
  min?: number;
}

export function ig(file: string) {
  return JSON.parse(readFileSync(join(import.meta.dirname, "..", "..", "..", "..", "ig", "resources", file), "utf8"));
}

export function requiredPaths(file: string): string[] {
  const elements: Element[] = ig(file).differential.element;
  const minOf = new Map(elements.map((element) => [element.id, element.min]));
  return elements
    .filter((element) => (element.min ?? 0) >= 1)
    .filter((element) => {
      const parts = element.id.split(".");
      return parts.slice(1, -1).every((_, depth) => (minOf.get(parts.slice(0, depth + 2).join(".")) ?? 1) >= 1);
    })
    .map((element) => element.id.split(".").slice(1).join("."));
}

export function present(node: unknown, path: string[]): boolean {
  const [head, ...rest] = path;
  if (head === undefined) return node !== undefined && node !== null && node !== "";
  const name = head.split(":")[0] ?? head;
  const value = (node as Record<string, unknown> | undefined)?.[name];
  const candidates = Array.isArray(value) ? value : [value];
  return candidates.some((candidate) => present(candidate, rest));
}

const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const FULL = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "shortOffset" });

const DAY_SECONDS = 86400;

// A consent ends by date, so an exp at midnight UTC reads as the end of the day before.
export function lastDay(exp: number): string | null {
  return exp % DAY_SECONDS === 0 ? DAY.format(new Date((exp - 1) * 1000)) : null;
}

export function fullTime(epochSeconds: number): string {
  return FULL.format(new Date(epochSeconds * 1000));
}

export function expiryUntil(exp: number): string {
  const day = lastDay(exp);
  return day ? `Until the end of ${day}` : fullTime(exp);
}

export function expiryAt(exp: number): string {
  const day = lastDay(exp);
  return day ? `at the end of ${day}` : fullTime(exp);
}

import { createServerFn } from "@tanstack/react-start";
import { getRequestUrl } from "@tanstack/react-start/server";

export const getSettings = createServerFn({ method: "GET" }).handler(async () => {
  const { loadSettings } = await import("./config");
  return loadSettings(getRequestUrl().origin);
});

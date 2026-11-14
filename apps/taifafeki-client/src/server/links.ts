import { createServerFn } from "@tanstack/react-start";

export const scanVhl = createServerFn({ method: "POST" })
  .validator((input: { token: string; text: string }) => input)
  .handler(async ({ data }) => {
    const { runtime } = await import("./runtime");
    const { scanLink } = await import("./viewer");
    return scanLink(runtime(), data);
  });

export const trustListStatuses = createServerFn({ method: "POST" })
  .validator((input: { token: string }) => input)
  .handler(async ({ data }) => {
    const { runtime } = await import("./runtime");
    const viewer = runtime();
    await viewer.session(data.token);
    return viewer.trust.statuses();
  });

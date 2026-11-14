import { createServerEntry, createStartHandler, defaultStreamHandler } from "@tanstack/react-start/server";

void import("./server/runtime").then(({ runtime }) => runtime());

export default createServerEntry({ fetch: createStartHandler(defaultStreamHandler) });

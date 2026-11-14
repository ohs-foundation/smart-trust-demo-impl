import { serve } from "srvx";
import { staticMiddleware } from "srvx/static";
import app from "./dist/server/server.js";

serve({
  port: Number(process.env.PORT ?? 3000),
  fetch: app.fetch,
  middleware: [staticMiddleware({ dir: "./dist/client" })],
});

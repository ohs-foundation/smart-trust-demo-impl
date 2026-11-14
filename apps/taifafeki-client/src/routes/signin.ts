import { createFileRoute } from "@tanstack/react-router";
import { beginSignIn } from "../auth/begin";
import { loadSettings } from "../server/config";

export const Route = createFileRoute("/signin")({
  server: {
    handlers: {
      GET: ({ request }) => {
        const { location, cookie } = beginSignIn(loadSettings(new URL(request.url).origin));
        return new Response(null, { status: 302, headers: { Location: location, "Set-Cookie": cookie } });
      },
    },
  },
});

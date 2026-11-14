import { HeadContent, Outlet, Scripts, createRootRoute } from "@tanstack/react-router";
import { SessionProvider } from "../session";
import styles from "../styles.css?url";

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "Taifafeki facility client" },
    ],
    links: [{ rel: "stylesheet", href: styles }],
  }),
  component: () => (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        <SessionProvider>
          <Outlet />
        </SessionProvider>
        <Scripts />
      </body>
    </html>
  ),
});

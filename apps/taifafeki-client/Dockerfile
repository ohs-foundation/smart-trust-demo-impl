# Build with the repo's build/hcert-js as the named context "hcert" (make hcert-js).
FROM node:22.22.0-slim
RUN npm install --global pnpm@10.12.1
WORKDIR /app/apps/taifafeki-client
COPY --from=hcert . /app/build/hcert-js
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build && pnpm prune --prod
ENV NODE_ENV=production
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]

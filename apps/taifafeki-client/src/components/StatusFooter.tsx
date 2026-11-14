interface StatusLine {
  label: string;
  detail: string;
  problems: string[];
}

export function StatusFooter({ title, lines }: { title: string; lines: StatusLine[] }) {
  return (
    <section className="mt-auto flex flex-col gap-1 border-t border-line pt-3 print:hidden">
      <h2 className="text-2xs font-semibold text-slate-600">{title}</h2>
      <ul className="flex flex-col gap-0.5 font-mono text-2xs text-slate-500">
        {lines.map((line) => (
          <li key={line.label}>
            {line.label}: {line.detail}
            {line.problems.map((problem) => (
              <span key={problem} role="alert" className="ml-2 text-bad-ink">
                {problem}
              </span>
            ))}
          </li>
        ))}
      </ul>
    </section>
  );
}

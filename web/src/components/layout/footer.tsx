"use client";

import { useEffect, useState } from "react";
import { Eye } from "lucide-react";
import { useTranslations } from "@/lib/i18n";

export function Footer() {
  const t = useTranslations("footer");
  const [count, setCount] = useState<number | null>(null);

  useEffect(() => {
    fetch(`${process.env.__NEXT_ROUTER_BASEPATH || ""}/api/pageview`, { method: "POST" })
      .then((res) => res.json())
      .then((data) => setCount(data.count))
      .catch(() => {});
  }, []);

  return (
    <footer className="border-t border-[var(--color-border)] bg-[var(--color-bg)]">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-6 sm:px-6 lg:px-8">
        <p className="text-xs text-zinc-400 dark:text-zinc-500">
          © 2025 AI Agent Learning
        </p>
        {count !== null && (
          <div className="flex items-center gap-1.5 text-xs text-zinc-400 dark:text-zinc-500">
            <Eye size={14} />
            <span>{t("views")}: {count.toLocaleString()}</span>
          </div>
        )}
      </div>
    </footer>
  );
}

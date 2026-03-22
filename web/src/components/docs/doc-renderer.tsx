"use client";

import { useMemo, useEffect, useRef, useCallback } from "react";
import { useLocale } from "@/lib/i18n";
import docsData from "@/data/generated/docs.json";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkRehype from "remark-rehype";
import rehypeRaw from "rehype-raw";
import rehypeHighlight from "rehype-highlight";
import rehypeStringify from "rehype-stringify";

interface DocRendererProps {
  lessonId: string;
}

function renderMarkdown(md: string): string {
  const result = unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkRehype, { allowDangerousHtml: true })
    .use(rehypeRaw)
    .use(rehypeHighlight, { detect: false, ignoreMissing: true })
    .use(rehypeStringify)
    .processSync(md);
  return String(result);
}

function postProcessHtml(html: string): string {
  html = html.replace(
    /<pre><code class="hljs language-(\w+)">/g,
    '<pre class="code-block" data-language="$1"><code class="hljs language-$1">'
  );

  html = html.replace(
    /<pre><code(?! class="hljs)([^>]*)>/g,
    '<pre class="ascii-diagram"><code$1>'
  );

  html = html.replace(/<blockquote>/, '<blockquote class="hero-callout">');

  html = html.replace(/<h1>.*?<\/h1>\n?/, "");

  html = html.replace(
    /<ol start="(\d+)">/g,
    (_, start) => `<ol style="counter-reset:step-counter ${parseInt(start) - 1}">`
  );

  return html;
}

export function DocRenderer({ lessonId }: DocRendererProps) {
  const locale = useLocale();

  const doc = useMemo(() => {
    const match = docsData.find(
      (d: { version: string; locale: string }) =>
        d.version === lessonId && d.locale === locale
    );
    if (match) return match;
    return docsData.find(
      (d: { version: string; locale: string }) =>
        d.version === lessonId && d.locale === "en"
    );
  }, [lessonId, locale]);

  if (!doc) {
    return (
      <div className="py-8 text-center text-zinc-400">
        Documentation not available yet.
      </div>
    );
  }

  const html = useMemo(() => {
    const raw = renderMarkdown(doc.content);
    return postProcessHtml(raw);
  }, [doc.content]);

  const contentRef = useRef<HTMLDivElement>(null);

  const handleCopyClick = useCallback((e: MouseEvent) => {
    const btn = e.currentTarget as HTMLButtonElement;
    const pre = btn.closest("pre");
    if (!pre) return;
    const code = pre.querySelector("code");
    if (!code) return;
    navigator.clipboard.writeText(code.textContent || "").then(() => {
      btn.textContent = "✓";
      btn.classList.add("copied");
      setTimeout(() => {
        btn.textContent = "Copy";
        btn.classList.remove("copied");
      }, 2000);
    });
  }, []);

  useEffect(() => {
    const container = contentRef.current;
    if (!container) return;
    const pres = container.querySelectorAll("pre.code-block");
    const buttons: HTMLButtonElement[] = [];
    pres.forEach((pre) => {
      if (pre.querySelector(".copy-btn")) return;
      const btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.textContent = "Copy";
      btn.addEventListener("click", handleCopyClick as EventListener);
      pre.appendChild(btn);
      buttons.push(btn);
    });
    return () => {
      buttons.forEach((btn) => {
        btn.removeEventListener("click", handleCopyClick as EventListener);
        btn.remove();
      });
    };
  }, [html, handleCopyClick]);

  return (
    <div className="py-4">
      <div
        ref={contentRef}
        className="prose-custom"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  );
}

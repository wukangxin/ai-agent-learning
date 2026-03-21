import { NextResponse } from "next/server";
import fs from "fs";
import path from "path";

const COUNT_FILE = path.join(process.cwd(), ".pageview-count.json");

function readCount(): number {
  try {
    if (fs.existsSync(COUNT_FILE)) {
      const data = JSON.parse(fs.readFileSync(COUNT_FILE, "utf-8"));
      return typeof data.count === "number" ? data.count : 0;
    }
  } catch {
    // ignore
  }
  return 0;
}

function writeCount(count: number) {
  fs.writeFileSync(COUNT_FILE, JSON.stringify({ count }, null, 2));
}

// GET: return current count
export async function GET() {
  return NextResponse.json({ count: readCount() });
}

// POST: increment and return new count
export async function POST() {
  const count = readCount() + 1;
  writeCount(count);
  return NextResponse.json({ count });
}

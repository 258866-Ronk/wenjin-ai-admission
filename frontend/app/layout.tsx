import type { Metadata } from "next";
import "./globals.css";
import { QueryProvider } from "@/lib/query-provider";

export const metadata: Metadata = { title: "问津智愿 AI", description: "高考志愿智能咨询助手" };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body><QueryProvider>{children}</QueryProvider></body></html>;
}


import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "Atlas · People & Operations",
  description: "A connected workspace for your people and operations.",
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

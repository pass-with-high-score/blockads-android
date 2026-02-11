import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import Link from "next/link";
import MobileNav from "./components/MobileNav";
import { Analytics } from "@vercel/analytics/next";

const inter = Inter({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "BlockAds – Ad Blocker for Android | No Root",
  description:
    "Block ads & trackers system-wide on Android. DNS filtering, no root needed. Free & open source.",
  metadataBase: new URL("https://blockads.pwhs.app/"),
  keywords: [
    "ad blocker",
    "block ads",
    "android",
    "dns filter",
    "privacy",
    "no root",
    "free",
    "open source",
  ],
  openGraph: {
    title: "BlockAds – Ad Blocker for Android",
    description:
      "Block ads system-wide. No root. DNS filtering. Free & open source!",
    type: "website",
    locale: "en_US",
    siteName: "BlockAds",
  },
  twitter: {
    card: "summary_large_image",
    title: "BlockAds – Ad Blocker for Android",
    description:
      "Block ads system-wide. No root. DNS filtering. Free & open source!",
  },
};

function Navbar() {
  return (
    <nav className="fixed top-0 left-0 right-0 z-50 backdrop-blur-xl bg-[#0a0a0f]/80 border-b border-white/5">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
        <Link href="/" className="flex items-center gap-2 group">
          <div className="w-8 h-8 rounded-lg bg-[#00E676] flex items-center justify-center text-black font-bold text-sm">
            B
          </div>
          <span className="font-semibold text-lg">
            Block<span className="text-[#00E676]">Ads</span>
          </span>
        </Link>

        {/* Desktop nav */}
        <div className="hidden md:flex items-center gap-6 text-sm text-[#8888a0]">
          <Link
            href="/#features"
            className="hover:text-white transition-colors"
          >
            Features
          </Link>
          <Link href="/privacy" className="hover:text-white transition-colors">
            Privacy
          </Link>
          <Link href="/terms" className="hover:text-white transition-colors">
            Terms
          </Link>
          <a
            href="https://play.google.com/store/apps/details?id=app.pwhs.blockads"
            target="_blank"
            rel="noopener noreferrer"
            className="px-4 py-2 bg-[#00E676] text-black font-medium rounded-full text-sm hover:bg-[#00C853] transition-colors"
          >
            Download
          </a>
        </div>

        {/* Mobile nav */}
        <MobileNav />
      </div>
    </nav>
  );
}

function Footer() {
  return (
    <footer className="border-t border-white/5 py-12 mt-20">
      <div className="max-w-6xl mx-auto px-4 sm:px-6">
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-8">
          <div>
            <div className="flex items-center gap-2 mb-4">
              <div className="w-7 h-7 rounded-lg bg-[#00E676] flex items-center justify-center text-black font-bold text-xs">
                B
              </div>
              <span className="font-semibold">
                Block<span className="text-[#00E676]">Ads</span>
              </span>
            </div>
            <p className="text-sm text-[#8888a0] leading-relaxed">
              Free, open-source ad blocker for Android.
              <br />
              No root. No data collection. No ads.
            </p>
          </div>
          <div>
            <h4 className="font-semibold text-sm mb-3">Links</h4>
            <div className="flex flex-col gap-2 text-sm text-[#8888a0]">
              <Link href="/" className="hover:text-white transition-colors">
                Home
              </Link>
              <Link
                href="/privacy"
                className="hover:text-white transition-colors"
              >
                Privacy Policy
              </Link>
              <Link
                href="/terms"
                className="hover:text-white transition-colors"
              >
                Terms of Service
              </Link>
            </div>
          </div>
          <div>
            <h4 className="font-semibold text-sm mb-3">Download</h4>
            <a
              href="https://play.google.com/store/apps/details?id=app.pwhs.blockads"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-block"
            >
              <img
                src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
                alt="Get it on Google Play"
                className="h-14"
              />
            </a>
          </div>
        </div>
        <div className="mt-10 pt-6 border-t border-white/5 text-center text-xs text-[#8888a0]">
          © {new Date().getFullYear()} BlockAds. All rights reserved.
        </div>
      </div>
    </footer>
  );
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className={`${inter.variable} antialiased`}>
        <Navbar />
        <main className="pt-16">{children}</main>
        <Footer />
        <Analytics />
      </body>
    </html>
  );
}

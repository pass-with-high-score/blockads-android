import {
  Smartphone,
  ShieldCheck,
  PartyPopper,
  ShieldOff,
  Lock,
  BarChart3,
  Settings,
  Palette,
  Zap,
  Save,
  Battery,
  Gift,
} from "lucide-react";

export default function Home() {
  return (
    <>
      {/* ── Hero ─────────────────────────────────────────── */}
      <section className="mesh-bg min-h-[90vh] flex items-center justify-center relative overflow-hidden px-4">
        {/* Decorative rings */}
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none overflow-hidden">
          <div className="w-[300px] md:w-[500px] h-[300px] md:h-[500px] rounded-full border border-[#00E676]/5 pulse-ring" />
        </div>

        <div className="text-center px-6 relative z-10 max-w-3xl mx-auto">
          {/* Badge */}
          <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full border border-[#00E676]/20 bg-[#00E676]/5 text-[#00E676] text-sm font-medium mb-8">
            <span className="w-2 h-2 bg-[#00E676] rounded-full animate-pulse" />
            Free & Open Source
          </div>

          {/* Title */}
          <h1 className="text-5xl md:text-7xl font-bold tracking-tight mb-6">
            Block Ads.
            <br />
            <span className="text-[#00E676] glow-text">Protect Privacy.</span>
          </h1>

          <p className="text-lg md:text-xl text-[#8888a0] max-w-xl mx-auto mb-10 leading-relaxed">
            System-wide ad blocking for Android using smart DNS filtering. No
            root required. No data collection. Just peace of mind.
          </p>

          {/* CTA */}
          <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
            <a
              href="https://play.google.com/store/apps/details?id=app.pwhs.blockads"
              target="_blank"
              rel="noopener noreferrer"
              className="px-8 py-4 bg-[#00E676] text-black font-semibold rounded-2xl text-lg hover:bg-[#00C853] transition-all hover:scale-105 glow"
            >
              Download for Android
            </a>
            <a
              href="https://github.com/nqmgaming/blockads-android"
              target="_blank"
              rel="noopener noreferrer"
              className="px-8 py-4 border border-white/10 text-white font-medium rounded-2xl text-lg hover:bg-white/5 transition-all"
            >
              View on GitHub
            </a>
          </div>

          {/* Stats */}
          <div className="flex flex-col sm:flex-row items-center justify-center gap-8 mt-14 text-sm text-[#8888a0]">
            <div className="flex flex-col items-center">
              <div className="text-2xl font-bold text-white">100%</div>
              <div>Free</div>
            </div>
            <div className="hidden sm:block w-px h-8 bg-white/10" />
            <div className="flex flex-col items-center">
              <div className="text-2xl font-bold text-white">0</div>
              <div>Data collected</div>
            </div>
            <div className="hidden sm:block w-px h-8 bg-white/10" />
            <div className="flex flex-col items-center">
              <div className="text-2xl font-bold text-white">No</div>
              <div>Root needed</div>
            </div>
          </div>
        </div>
      </section>

      {/* ── How it works ────────────────────────────────── */}
      <section className="py-24 px-6">
        <div className="max-w-5xl mx-auto text-center mb-16">
          <h2 className="text-3xl md:text-4xl font-bold mb-4">
            How It Works
          </h2>
          <p className="text-[#8888a0] max-w-lg mx-auto">
            Three simple steps to an ad-free Android experience.
          </p>
        </div>

        <div className="max-w-4xl mx-auto grid grid-cols-1 md:grid-cols-3 gap-6">
          {[
            {
              step: "1",
              title: "Open BlockAds",
              desc: "Launch the app and tap the power button.",
              icon: Smartphone,
            },
            {
              step: "2",
              title: "Allow VPN",
              desc: "A local VPN is created to filter DNS queries.",
              icon: ShieldCheck,
            },
            {
              step: "3",
              title: "Enjoy!",
              desc: "Ads are blocked across all apps and browsers.",
              icon: PartyPopper,
            },
          ].map((item) => (
            <div
              key={item.step}
              className="relative p-6 rounded-2xl bg-[#12121a] border border-white/5 hover:border-[#00E676]/20 transition-all group"
            >
              <item.icon className="w-12 h-12 mb-4 text-[#00E676]" />
              <div className="text-xs font-mono text-[#00E676] mb-2">
                Step {item.step}
              </div>
              <h3 className="text-lg font-semibold mb-2">{item.title}</h3>
              <p className="text-sm text-[#8888a0]">{item.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── Features ────────────────────────────────────── */}
      <section id="features" className="py-24 px-6 mesh-bg">
        <div className="max-w-5xl mx-auto text-center mb-16">
          <h2 className="text-3xl md:text-4xl font-bold mb-4">
            Packed with Features
          </h2>
          <p className="text-[#8888a0] max-w-lg mx-auto">
            Everything you need for a clean, private Android experience.
          </p>
        </div>

        <div className="max-w-5xl mx-auto grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {[
            {
              icon: ShieldOff,
              title: "System-wide Blocking",
              desc: "Block ads in every app and browser using DNS filtering.",
            },
            {
              icon: Lock,
              title: "Privacy Protection",
              desc: "Block trackers and spyware. All data stays on your device.",
            },
            {
              icon: BarChart3,
              title: "Real-time Stats",
              desc: "24-hour activity chart, blocked count, and detailed DNS logs.",
            },
            {
              icon: Settings,
              title: "Multiple Filter Lists",
              desc: "ABPVN, AdGuard DNS, EasyList, and custom URL support.",
            },
            {
              icon: Palette,
              title: "Modern UI",
              desc: "Material 3 design with Dark, Light, and System themes.",
            },
            {
              icon: Zap,
              title: "Quick Settings Tile",
              desc: "Toggle protection from the notification shade instantly.",
            },
            {
              icon: Save,
              title: "Backup & Restore",
              desc: "Export settings to JSON and restore on any device.",
            },
            {
              icon: Battery,
              title: "Battery Friendly",
              desc: "Only filters DNS packets — minimal battery impact.",
            },
            {
              icon: Gift,
              title: "Free Forever",
              desc: "No ads, no in-app purchases, no subscriptions. Ever.",
            },
          ].map((feature) => (
            <div
              key={feature.title}
              className="p-5 rounded-2xl bg-[#12121a]/80 border border-white/5 hover:border-[#00E676]/15 transition-all"
            >
              <feature.icon className="w-8 h-8 mb-3 text-[#00E676]" />
              <h3 className="font-semibold mb-1.5">{feature.title}</h3>
              <p className="text-sm text-[#8888a0] leading-relaxed">
                {feature.desc}
              </p>
            </div>
          ))}
        </div>
      </section>

      {/* ── Technical ───────────────────────────────────── */}
      <section className="py-24 px-6">
        <div className="max-w-3xl mx-auto">
          <h2 className="text-3xl md:text-4xl font-bold mb-4 text-center">
            Built with Trust
          </h2>
          <p className="text-[#8888a0] text-center max-w-lg mx-auto mb-12">
            Privacy is not just a feature — it&apos;s our foundation.
          </p>

          <div className="space-y-4">
            {[
              {
                q: "Is BlockAds safe?",
                a: "Yes. The app only filters DNS queries locally. No personal data is intercepted or transmitted.",
              },
              {
                q: "Why does it need VPN permission?",
                a: "A local VPN tunnel is created on your device to intercept DNS requests. No data leaves your phone.",
              },
              {
                q: "Does it work with all apps?",
                a: "Yes. BlockAds works system-wide. You can whitelist specific apps if needed.",
              },
              {
                q: "Is it really free?",
                a: "100% free, no ads in the app, no in-app purchases, and the source code is open.",
              },
            ].map((faq) => (
              <div
                key={faq.q}
                className="p-5 rounded-2xl bg-[#12121a] border border-white/5"
              >
                <h3 className="font-semibold mb-2">{faq.q}</h3>
                <p className="text-sm text-[#8888a0] leading-relaxed">
                  {faq.a}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── CTA ─────────────────────────────────────────── */}
      <section className="py-24 px-6 text-center mesh-bg">
        <div className="max-w-2xl mx-auto">
          <h2 className="text-3xl md:text-4xl font-bold mb-4">
            Ready for an Ad-Free Life?
          </h2>
          <p className="text-[#8888a0] mb-10">
            Download BlockAds and take back control of your Android experience.
          </p>
          <a
            href="https://play.google.com/store/apps/details?id=app.pwhs.blockads"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-block px-10 py-4 bg-[#00E676] text-black font-semibold rounded-2xl text-lg hover:bg-[#00C853] transition-all hover:scale-105 glow"
          >
            Get BlockAds — Free
          </a>
        </div>
      </section>
    </>
  );
}

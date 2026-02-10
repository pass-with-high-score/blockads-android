import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Privacy Policy – BlockAds",
    description:
        "Privacy Policy for BlockAds ad blocker app. Learn how we protect your data.",
};

export default function PrivacyPolicy() {
    return (
        <div className="max-w-3xl mx-auto px-6 py-20">
            <h1 className="text-4xl font-bold mb-2">Privacy Policy</h1>
            <p className="text-sm text-[#8888a0] mb-10">
                Last updated: February 10, 2025
            </p>

            <div className="legal-content">
                <h2>1. Overview</h2>
                <p>
                    BlockAds (&quot;we&quot;, &quot;our&quot;, or &quot;the app&quot;) is a
                    free, open-source ad blocker for Android. We are committed to
                    protecting your privacy. This Privacy Policy explains what data we
                    collect (or rather, what we don&apos;t collect) and how the app works.
                </p>

                <h2>2. Data We Collect</h2>
                <h3>2.1 We Do NOT Collect</h3>
                <ul>
                    <li>Personal information (name, email, phone number)</li>
                    <li>Device identifiers or advertising IDs</li>
                    <li>Location data</li>
                    <li>Browsing history</li>
                    <li>Usage analytics or telemetry</li>
                    <li>Any data transmitted to external servers</li>
                </ul>

                <h3>2.2 Data Stored Locally</h3>
                <p>
                    The following data is stored <strong>only on your device</strong> and
                    is never transmitted to any server:
                </p>
                <ul>
                    <li>
                        <strong>DNS Query Logs:</strong> Records of DNS queries processed by
                        the app, including the domain name, timestamp, and whether the query
                        was blocked. These logs are stored in a local SQLite database.
                    </li>
                    <li>
                        <strong>App Settings:</strong> Your preferences such as filter list
                        selections, DNS server configuration, theme mode, and whitelisted
                        apps/domains.
                    </li>
                    <li>
                        <strong>Filter Lists:</strong> Cached copies of ad-blocking filter
                        lists downloaded from public sources.
                    </li>
                </ul>

                <h2>3. VPN Service</h2>
                <p>
                    BlockAds uses Android&apos;s VPN Service to create a{" "}
                    <strong>local VPN tunnel</strong> on your device. This is necessary to
                    intercept and filter DNS queries.
                </p>
                <ul>
                    <li>
                        The VPN tunnel is <strong>entirely local</strong> — it does not route
                        your traffic through any external server.
                    </li>
                    <li>
                        Only DNS traffic (port 53) is intercepted. All other internet
                        traffic passes through normally.
                    </li>
                    <li>
                        No internet traffic content is logged, inspected, modified, or
                        transmitted.
                    </li>
                </ul>

                <h2>4. Filter Lists</h2>
                <p>
                    BlockAds downloads filter lists from publicly available sources (e.g.,
                    ABPVN, AdGuard DNS, EasyList). These downloads contain only the filter
                    list data — no personal information is sent to the filter list
                    providers.
                </p>

                <h2>5. Third-Party Services</h2>
                <p>
                    BlockAds does <strong>not</strong> integrate any third-party services
                    such as:
                </p>
                <ul>
                    <li>Analytics SDKs (Google Analytics, Firebase, etc.)</li>
                    <li>Advertising SDKs</li>
                    <li>Crash reporting services</li>
                    <li>Social media trackers</li>
                </ul>

                <h2>6. Data Retention & Deletion</h2>
                <p>
                    All data is stored locally on your device. You can delete all stored
                    data at any time through:
                </p>
                <ul>
                    <li>
                        <strong>Settings → Clear All Logs</strong> to delete DNS query logs
                    </li>
                    <li>
                        <strong>Uninstalling the app</strong> to remove all app data
                    </li>
                </ul>

                <h2>7. Children&apos;s Privacy</h2>
                <p>
                    BlockAds is not directed at children under the age of 13. We do not
                    knowingly collect any personal information from children.
                </p>

                <h2>8. Export & Import</h2>
                <p>
                    The app allows you to export your settings to a JSON file and import
                    them on another device. This file is created and managed entirely by
                    you — it is not uploaded to any server. The exported file contains only
                    your app configuration (filter lists, DNS settings, whitelisted
                    apps/domains).
                </p>

                <h2>9. Changes to This Policy</h2>
                <p>
                    We may update this Privacy Policy from time to time. The updated
                    version will be posted on this page with a revised &quot;Last
                    updated&quot; date. We encourage you to review this page periodically.
                </p>

                <h2>10. Contact Us</h2>
                <p>
                    If you have any questions about this Privacy Policy, please contact us
                    at:
                </p>
                <p>
                    <strong>Email:</strong>{" "}
                    <a
                        href="mailto:contact@pwhs.app"
                        className="text-[#00E676] hover:underline"
                    >
                        contact@pwhs.app
                    </a>
                </p>
            </div>
        </div>
    );
}

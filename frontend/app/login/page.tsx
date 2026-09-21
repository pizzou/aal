"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { authApi, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

const RESEND_COOLDOWN_SECONDS = 30;

export default function LoginPage() {
  const router = useRouter();
  const search = useSearchParams();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [challenge, setChallenge] = useState<string | null>(null);
  const [step, setStep] = useState<"credentials" | "otp">("credentials");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [resendIn, setResendIn] = useState(0);

  useEffect(() => {
    if (resendIn <= 0) return;
    const timer = window.setInterval(() => {
      setResendIn((value) => Math.max(0, value - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [resendIn]);

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;

    setBusy(true);
    setError("");

    try {
      if (step === "credentials") {
        /*
         * The backend issues and sends the first OTP atomically during login.
         * Do not call /send-login-otp here: doing so would generate a second
         * code immediately after the first one and could leave the user with
         * two different verification emails.
         */
        const r = await authApi.login(email, password);

        if (r.otpRequired && r.otpChallengeToken) {
          setChallenge(r.otpChallengeToken);
          setOtp("");
          setStep("otp");
          setResendIn(RESEND_COOLDOWN_SECONDS);
          return;
        }

        await finish(r);
        return;
      }

      if (!challenge) {
        throw new Error("Verification session expired. Sign in again.");
      }

      if (!/^\d{6}$/.test(otp)) {
        throw new Error("Enter the 6-digit verification code.");
      }

      const r = await authApi.login(email, password, otp, challenge);
      await finish(r);
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Authentication failed",
      );
    } finally {
      setBusy(false);
    }
  }

  async function resendOtp() {
    if (!challenge || busy || resendIn > 0) return;

    setBusy(true);
    setError("");

    try {
      await authApi.sendLoginOtp(challenge);
      setOtp("");
      setResendIn(RESEND_COOLDOWN_SECONDS);
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : "Could not resend the verification code",
      );
    } finally {
      setBusy(false);
    }
  }

  async function finish(r: {
    accessToken: string | null;
    tenantId: string;
    role: string;
    mustChangePassword: boolean;
  }) {
    if (r.role === "CUSTOMER") {
      try {
        await authApi.logout();
      } catch {
        // Keep the internal workspace closed even if cleanup fails.
      }
      setError(
        "Customer portal access is disabled. Customers use AAL's public quote, booking and tracking services without an account.",
      );
      return;
    }

    login(r.accessToken, r.tenantId, r.role);

    const next = search.get("next");
    const destination =
      next && next.startsWith("/") && !next.startsWith("/login")
        ? next
        : "/aal-control-tower";

    router.replace(r.mustChangePassword ? "/account/security" : destination);
  }

  function backToCredentials() {
    setStep("credentials");
    setChallenge(null);
    setOtp("");
    setError("");
    setResendIn(0);
  }

  return (
    <main className="aal-login-shell">
      <section className="aal-login-brand-panel">
        <div className="aal-login-brand-header">
          <img
            src="/branding/aal-logo.jpg"
            alt="Aviation Africa Logistics Ltd"
            className="aal-login-logo-compact"
          />
          <div>
            <strong>AVIATION AFRICA LOGISTICS LTD</strong>
            <span>GLOBAL REACH · AFRICAN ROOTS</span>
          </div>
        </div>

        <div className="aal-login-brand-content">
          <div className="aal-login-brand-art">
            <img
              src="/branding/aal-brand.png"
              alt="Aviation Africa Logistics Ltd"
            />
          </div>
          <div className="eyebrow aal-login-eyebrow">
            OPERATIONS CONTROL TOWER
          </div>
          <h1>Move freight with clarity, speed and control.</h1>
          <p>
            Coordinate air cargo, road, ocean, rail, warehouse, customs, finance
            and shipment visibility from one secure logistics workspace.
          </p>
        </div>

        <div className="aal-login-capabilities">
          <span>
            <b className="brand-accent-yellow">AIR</b> FREIGHT
          </span>
          <span>
            <b className="brand-accent-red">OCEAN</b> FREIGHT
          </span>
          <span>
            <b className="brand-accent-yellow">ROAD</b> FREIGHT
          </span>
          <span>
            <b className="brand-accent-red">RAIL</b> FREIGHT
          </span>
          <span>
            <b className="brand-accent-blue">WAREHOUSE</b>
          </span>
        </div>
      </section>

      <section className="aal-login-form-panel">
        <div className="aal-login-card">
          <div className="aal-login-card-logo">
            <img src="/branding/aal-logo.jpg" alt="AAL logo" />
          </div>
          <div className="eyebrow">SECURE AAL WORKSPACE</div>
          <h2>{step === "otp" ? "Verify your sign-in" : "Welcome back"}</h2>
          <p className="page-subtitle">
            {step === "otp"
              ? `A 6-digit verification code was sent to ${email}. It expires in 5 minutes.`
              : "Sign in to the Aviation Africa Logistics operations control tower."}
          </p>

          <form className="aal-login-form" onSubmit={submit}>
            {step === "credentials" ? (
              <>
                <div className="field">
                  <label htmlFor="aal-email">Operations email</label>
                  <input
                    id="aal-email"
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                    placeholder="name@company.com"
                  />
                </div>
                <div className="field">
                  <label htmlFor="aal-password">Password</label>
                  <input
                    id="aal-password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    autoComplete="current-password"
                    placeholder="Enter your password"
                  />
                </div>
              </>
            ) : (
              <div className="field">
                <label htmlFor="aal-otp">Email verification code</label>
                <input
                  id="aal-otp"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                  required
                  autoFocus
                  autoComplete="one-time-code"
                  placeholder="000000"
                  className="aal-otp-input"
                />
              </div>
            )}

            {error && <div className="alert alert-error">{error}</div>}

            <button
              className="btn btn-primary btn-large btn-block"
              disabled={busy}
            >
              {busy
                ? step === "otp"
                  ? "Verifying…"
                  : "Authenticating…"
                : step === "otp"
                  ? "Verify & Enter"
                  : "Continue"}
              {!busy && <Icon name="arrow" size={15} />}
            </button>

            {step === "otp" && (
              <>
                <button
                  type="button"
                  className="btn btn-block"
                  disabled={busy || resendIn > 0 || !challenge}
                  onClick={() => void resendOtp()}
                >
                  {resendIn > 0
                    ? `Resend code in ${resendIn}s`
                    : "Resend verification code"}
                </button>
                <button
                  type="button"
                  className="aal-secondary-link"
                  onClick={backToCredentials}
                  disabled={busy}
                >
                  Back to sign in
                </button>
              </>
            )}
          </form>

          {step === "credentials" && (
            <div className="aal-login-forgot">
              <a href="/forgot-password">Forgot password?</a>
            </div>
          )}

          <div className="aal-login-security-note">
            <Icon name="shield" size={14} />
            <span>
              Protected with secure session cookies, CSRF controls and email
              verification.
            </span>
          </div>
        </div>
      </section>
    </main>
  );
}

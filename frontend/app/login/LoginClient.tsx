"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { authApi, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

const RESEND_COOLDOWN_SECONDS = 30;

export default function LoginClient() {
  const router = useRouter();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [challenge, setChallenge] = useState<string | null>(null);
  const [step, setStep] = useState<"credentials" | "otp">("credentials");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [codeRequestAccepted, setCodeRequestAccepted] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const deliveryAttempt = useRef(0);

  useEffect(() => {
    if (resendIn <= 0) return;
    const timer = window.setInterval(() => {
      setResendIn((value) => Math.max(0, value - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [resendIn]);

  /**
   * A repeat OTP is sent only when the user explicitly presses Resend.
   * The initial code is delivered synchronously by /api/auth/login before
   * the server returns its signed challenge.
   * A timeout during resend must not send the user back to the password screen:
   * Brevo may already have accepted the email even if its response was lost.
   */
  async function deliverCode(token: string) {
    const attempt = ++deliveryAttempt.current;
    setSendingCode(true);
    setCodeRequestAccepted(false);
    setError("");
    // Apply the cooldown even if the transport times out; blindly retrying
    // might invalidate the code that was already delivered.
    setResendIn(RESEND_COOLDOWN_SECONDS);

    try {
      await authApi.sendLoginOtp(token);
      if (attempt === deliveryAttempt.current) setCodeRequestAccepted(true);
    } catch (err) {
      if (attempt !== deliveryAttempt.current) return;
      if (
        err instanceof ApiError &&
        (err.status === 408 || err.status === 504)
      ) {
        setError(
          "Email delivery took longer than expected. If you received a code, enter it below. Otherwise, wait for the resend option. You do not need to sign in again.",
        );
      } else if (err instanceof ApiError && err.status === 429) {
        setError(
          "A verification code was recently requested. Check your email for the latest code or use Resend when it becomes available.",
        );
      } else {
        setError(
          err instanceof ApiError
            ? err.message
            : err instanceof Error
              ? err.message
              : "The verification email could not be sent. Try Resend.",
        );
        // A definite provider rejection is not an uncertain timeout; permit
        // a fresh delivery attempt (server-side rate limiting remains active).
        setResendIn(0);
      }
    } finally {
      if (attempt === deliveryAttempt.current) setSendingCode(false);
    }
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;

    setBusy(true);
    setError("");

    try {
      if (step === "credentials") {
        // The backend sends the initial OTP and waits for Brevo acceptance
        // before returning the challenge. Do not fire a second send request:
        // doing so would immediately hit the 30-second OTP resend cooldown.
        const r = await authApi.login(email, password);

        if (r.otpRequired) {
          if (!r.otpChallengeToken) {
            throw new Error(
              "Verification could not start. Try signing in again.",
            );
          }
          setChallenge(r.otpChallengeToken);
          setOtp("");
          setCodeRequestAccepted(true);
          setResendIn(RESEND_COOLDOWN_SECONDS);
          setStep("otp");
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

  function resendOtp() {
    if (!challenge || busy || sendingCode || resendIn > 0) return;
    void deliverCode(challenge);
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

    // Every successful internal sign-in starts at the Command Center.
    // Mandatory password changes still take precedence for account security.
    router.replace(
      r.mustChangePassword ? "/account/security" : "/aal-control-tower",
    );
  }

  function backToCredentials() {
    // Ignore the completion of any still-running email request from this step.
    deliveryAttempt.current += 1;
    setSendingCode(false);
    setCodeRequestAccepted(false);
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
              ? sendingCode
                ? `Sending a 6-digit verification code to ${email}…`
                : codeRequestAccepted
                  ? `The email provider accepted your verification code request for ${email}. Enter the 6-digit code when it arrives (valid for 5 minutes).`
                  : `Email delivery was not confirmed. Check the error below or use Resend.`
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

            {step === "otp" && sendingCode && (
              <p className="page-subtitle" role="status" aria-live="polite">
                Requesting your verification email… You can stay on this screen.
              </p>
            )}
            {step === "otp" &&
              codeRequestAccepted &&
              !sendingCode &&
              !error && (
                <p className="page-subtitle" role="status" aria-live="polite">
                  Verification email accepted for sending. Check your inbox and
                  spam folder.
                </p>
              )}
            {error && (
              <div className="alert alert-error" role="alert">
                {error}
              </div>
            )}

            <button
              className="btn btn-primary btn-large btn-block"
              disabled={busy}
            >
              {busy
                ? step === "otp"
                  ? "Verifying…"
                  : "Authenticating and sending email…"
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
                  disabled={busy || sendingCode || resendIn > 0 || !challenge}
                  onClick={resendOtp}
                >
                  {sendingCode
                    ? "Sending verification code…"
                    : resendIn > 0
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

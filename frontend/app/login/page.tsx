"use client";

import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { authApi, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import Icon from "@/components/Icon";

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

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");

    try {
      if (step === "credentials") {
        const r = await authApi.login(email, password);

        if (r.otpRequired && r.otpChallengeToken) {
          setChallenge(r.otpChallengeToken);
          await authApi.sendLoginOtp(r.otpChallengeToken);
          setStep("otp");
          return;
        }

        finish(r);
        return;
      }

      if (!challenge) {
        throw new Error("Verification session expired. Sign in again.");
      }

      const r = await authApi.login(email, password, otp, challenge);
      finish(r);
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
    if (!challenge) return;

    setBusy(true);
    setError("");

    try {
      await authApi.sendLoginOtp(challenge);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not resend code");
    } finally {
      setBusy(false);
    }
  }

  function finish(r: any) {
    login(r.accessToken, r.tenantId, r.role);
    const next = search.get("next");
    const destination = next?.startsWith("/") ? next : "/aal-control-tower";
    router.push(r.mustChangePassword ? "/account/security" : destination);
  }

  return (
    <main
      style={{
        minHeight: "100vh",
        display: "grid",
        gridTemplateColumns: "1.1fr .9fr",
        background: "#f5f7fb",
      }}
    >
      <section
        style={{
          background: "linear-gradient(145deg,#08111f,#102a56)",
          color: "#fff",
          padding: "8vh 8vw",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
        }}
      >
        <div>
          <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
            <div className="brand-mark">A</div>
            <div>
              <div style={{ fontWeight: 800, fontSize: 20 }}>
                Africa Logistic Aviation
              </div>
              <div style={{ opacity: 0.65, fontSize: 12 }}>
                Logistics Operations Platform
              </div>
            </div>
          </div>

          <div style={{ maxWidth: 620, marginTop: "18vh" }}>
            <div
              style={{
                color: "#84caff",
                fontWeight: 700,
                fontSize: 12,
                letterSpacing: ".12em",
              }}
            >
              AAL OPERATIONS CONTROL TOWER
            </div>
            <h1
              style={{
                fontSize: "clamp(38px,5vw,68px)",
                lineHeight: 1.02,
                letterSpacing: "-.05em",
                margin: "16px 0",
              }}
            >
              Move freight with clarity, speed and control.
            </h1>
            <p
              style={{
                fontSize: 17,
                lineHeight: 1.7,
                color: "#cbd5e1",
              }}
            >
              Coordinate air cargo, road operations, warehouse inventory,
              documents, billing and shipment visibility from one controlled AAL
              operations workspace.
            </p>
          </div>
        </div>

        <div
          style={{
            display: "flex",
            gap: 22,
            color: "#98a2b3",
            fontSize: 12,
            flexWrap: "wrap",
          }}
        >
          <span>Single client workspace</span>
          <span>Air cargo ready</span>
          <span>Multimodal execution</span>
          <span>Secure operations</span>
        </div>
      </section>

      <section style={{ display: "grid", placeItems: "center", padding: 24 }}>
        <div
          style={{
            width: "min(430px,100%)",
            background: "#fff",
            border: "1px solid #e4e7ec",
            borderRadius: 16,
            padding: 32,
            boxShadow: "0 20px 60px rgba(16,24,40,.08)",
          }}
        >
          <div className="eyebrow">SECURE AAL WORKSPACE</div>
          <h2 style={{ fontSize: 28, margin: "7px 0" }}>
            {step === "otp" ? "Verify your sign-in" : "Welcome back"}
          </h2>
          <p className="page-subtitle">
            {step === "otp"
              ? `A 6-digit verification code was sent to ${email}. It expires in 5 minutes.`
              : "Sign in to the Africa Logistic Aviation operations control tower."}
          </p>

          <form
            onSubmit={submit}
            style={{ display: "grid", gap: 14, marginTop: 25 }}
          >
            {step === "credentials" ? (
              <>
                <div className="field">
                  <label>Operations email</label>
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                    autoComplete="username"
                  />
                </div>
                <div className="field">
                  <label>Password</label>
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    autoComplete="current-password"
                  />
                </div>
              </>
            ) : (
              <div className="field">
                <label>Email verification code</label>
                <input
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                  required
                  autoFocus
                  placeholder="000000"
                />
              </div>
            )}

            {error && <div className="alert alert-error">{error}</div>}

            <button className="btn btn-primary" disabled={busy}>
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
                  className="btn"
                  disabled={busy || !challenge}
                  onClick={resendOtp}
                >
                  Resend code
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    setStep("credentials");
                    setChallenge(null);
                    setOtp("");
                  }}
                >
                  Back to sign in
                </button>
              </>
            )}
          </form>

          {step === "credentials" && (
            <div style={{ marginTop: 16, textAlign: "right" }}>
              <a href="/forgot-password" className="page-subtitle">
                Forgot password?
              </a>
            </div>
          )}

          <div
            style={{
              marginTop: 22,
              paddingTop: 18,
              borderTop: "1px solid #eaecf0",
              color: "#667085",
              fontSize: 11,
              lineHeight: 1.6,
            }}
          >
            This workspace is provisioned exclusively for Africa Logistic
            Aviation. New organizations and self-service registration are
            disabled.
          </div>
        </div>
      </section>
    </main>
  );
}

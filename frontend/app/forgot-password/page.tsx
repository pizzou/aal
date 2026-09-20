"use client";

import { useState } from "react";
import { authApi, ApiError } from "@/lib/api-client";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;

    setBusy(true);
    setError("");

    try {
      await authApi.forgotPassword(email.trim().toLowerCase());
      setSent(true);
    } catch (exception) {
      setError(
        exception instanceof ApiError
          ? exception.message
          : "Unable to process the password reset request",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="aal-simple-auth-page">
      <section className="aal-simple-auth-card">
        <img
          src="/branding/aal-logo.jpg"
          alt="Aviation Africa Logistics Ltd"
          className="aal-simple-auth-logo"
        />
        <div className="eyebrow">ACCOUNT RECOVERY</div>
        <h1 className="page-title">Reset your password</h1>
        <p className="page-subtitle">
          Enter your AAL account email. If the account exists, a secure reset
          link will be sent.
        </p>

        {sent ? (
          <div className="alert">
            Check your email for the password reset link. The link is time
            limited for your security.
          </div>
        ) : (
          <form className="form-grid" onSubmit={submit}>
            <div className="field">
              <label htmlFor="recovery-email">Email</label>
              <input
                id="recovery-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                required
                autoComplete="email"
                autoCapitalize="none"
                spellCheck={false}
              />
            </div>
            {error && <div className="alert alert-error">{error}</div>}
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? "Sending…" : "Send reset link"}
            </button>
          </form>
        )}

        <a href="/login" className="aal-auth-back-link">
          Back to secure sign in
        </a>
      </section>
    </main>
  );
}

"use client";

import { useSearchParams, useRouter } from "next/navigation";
import { useState } from "react";
import { authApi, ApiError } from "@/lib/api-client";

export default function ResetPasswordPage() {
  const search = useSearchParams();
  const router = useRouter();
  const token = search.get("token") || "";

  const [nextPassword, setNextPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [ok, setOk] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;

    setError("");

    if (!token) {
      setError("This password reset link is missing or invalid.");
      return;
    }

    if (
      nextPassword.length < 10 ||
      nextPassword.length > 128 ||
      !/[A-Z]/.test(nextPassword) ||
      !/[a-z]/.test(nextPassword) ||
      !/[0-9]/.test(nextPassword) ||
      !/[^A-Za-z0-9]/.test(nextPassword)
    ) {
      setError(
        "Password must be 10-128 characters and include uppercase, lowercase, digit and special character.",
      );
      return;
    }

    if (nextPassword !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setBusy(true);

    try {
      await authApi.resetPassword(token, nextPassword);
      setOk(true);
      window.setTimeout(() => router.replace("/login"), 1200);
    } catch (exception) {
      setError(
        exception instanceof ApiError
          ? exception.message
          : "Reset link is invalid or expired",
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
        <h1 className="page-title">Create a new password</h1>
        <p className="page-subtitle">
          Use a strong password that you do not reuse on another service.
        </p>

        {ok ? (
          <div className="alert">
            Password reset successfully. Returning to secure sign in…
          </div>
        ) : (
          <form className="form-grid" onSubmit={submit}>
            <div className="field">
              <label htmlFor="new-password">New password</label>
              <input
                id="new-password"
                type="password"
                value={nextPassword}
                onChange={(event) => setNextPassword(event.target.value)}
                required
                autoComplete="new-password"
              />
            </div>
            <div className="field">
              <label htmlFor="confirm-password">Confirm password</label>
              <input
                id="confirm-password"
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                required
                autoComplete="new-password"
              />
            </div>
            {error && <div className="alert alert-error">{error}</div>}
            <button
              className="btn btn-primary btn-block"
              disabled={busy || !token}
            >
              {busy ? "Resetting…" : "Reset password"}
            </button>
          </form>
        )}
      </section>
    </main>
  );
}

"use client";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import {
  ApiError,
  PublicShipmentView,
  publicTrackingApi,
} from "@/lib/api-client";
import Icon from "@/components/Icon";
export default function PublicTrackingResult() {
  const params = useParams<{ token: string }>();
  const [data, setData] = useState<PublicShipmentView | null>(null);
  const [error, setError] = useState("");
  const [feedbackRating, setFeedbackRating] = useState(5);
  const [feedbackComment, setFeedbackComment] = useState("");
  const [feedbackSent, setFeedbackSent] = useState(false);
  useEffect(() => {
    if (params.token)
      publicTrackingApi
        .track(params.token)
        .then(setData)
        .catch((e) =>
          setError(
            e instanceof ApiError ? e.message : "Tracking reference not found",
          ),
        );
  }, [params.token]);
  if (error)
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="flow-success-icon">
            <Icon name="search" size={30} />
          </div>
          <div className="public-eyebrow">03 / TRACK</div>
          <h1>We could not find that shipment.</h1>
          <p>{error}. Check the reference and try again.</p>
          <Link className="public-main-button" href="/track">
            Try another reference
          </Link>
        </section>
      </main>
    );
  if (!data)
    return (
      <main className="public-flow">
        <FlowNav />
        <section className="flow-success">
          <div className="public-eyebrow">03 / TRACK</div>
          <h1>Loading shipment visibility…</h1>
        </section>
      </main>
    );
  return (
    <main className="public-flow">
      <FlowNav />
      <section className="track-result">
        <div className="track-result-head">
          <div>
            <div className="public-eyebrow">03 / LIVE SHIPMENT VISIBILITY</div>
            <h1>{data.referenceCode}</h1>
            <p>
              {data.originAddress} <span>→</span> {data.destinationAddress}
            </p>
          </div>
          <span className="track-status">{data.status}</span>
        </div>
        <div className="track-kpis">
          <div>
            <span>MODE</span>
            <strong>{data.transportMode}</strong>
          </div>
          <div>
            <span>CARRIER</span>
            <strong>{data.carrierName || "Assigned by AAL"}</strong>
          </div>
          <div>
            <span>REFERENCE</span>
            <strong>{data.carrierReferenceNumber || data.referenceCode}</strong>
          </div>
          <div>
            <span>ETA</span>
            <strong>
              {data.eta ? new Date(data.eta).toLocaleString() : "Updating"}
            </strong>
          </div>
        </div>
        <section className="track-panel">
          <div className="track-panel-title">
            <span>SHIPMENT JOURNEY</span>
            <strong>Milestones</strong>
          </div>
          {data.events.map((x, i) => (
            <div className="track-event" key={`${x.occurredAt}-${i}`}>
              <div className="track-dot" />
              <div>
                <strong>{x.eventType}</strong>
                <span>{x.location || "AAL network"}</span>
                <small>{x.notes || "Shipment milestone recorded"}</small>
              </div>
              <time>{new Date(x.occurredAt).toLocaleString()}</time>
            </div>
          ))}
          {!data.events.length && (
            <div className="empty">
              Shipment booked. AAL will publish additional milestones as the
              movement progresses.
            </div>
          )}
        </section>
        <div className="track-bottom">
          <div className="track-panel">
            <div className="track-panel-title">
              <span>DOCUMENTS</span>
              <strong>Customer-visible documents</strong>
            </div>
            {data.documents.map((d, i) => (
              <div className="track-doc" key={`${d.documentType}-${i}`}>
                <Icon name="file" size={16} />
                <div>
                  <strong>{d.documentType}</strong>
                  <small>
                    {d.status} · {new Date(d.createdAt).toLocaleDateString()}
                  </small>
                </div>
              </div>
            ))}
            {!data.documents.length && (
              <div className="empty">
                No public documents have been published.
              </div>
            )}
          </div>
          <div className="track-panel">
            <div className="track-panel-title">
              <span>DELIVERY</span>
              <strong>Proof of delivery</strong>
            </div>
            {data.pod ? (
              <>
                <div className="pod-line">
                  <span>Recipient</span>
                  <strong>{data.pod.recipientName || "—"}</strong>
                </div>
                <div className="pod-line">
                  <span>Delivered</span>
                  <strong>
                    {new Date(data.pod.deliveredAt).toLocaleString()}
                  </strong>
                </div>
                <div className="pod-line">
                  <span>Evidence</span>
                  <strong>
                    {data.pod.evidenceAvailable ? "Available" : "Not attached"}
                  </strong>
                </div>
              </>
            ) : (
              <div className="empty">
                POD will appear here after delivery confirmation.
              </div>
            )}
          </div>
        </div>
        <section className="track-panel" style={{ marginTop: 18 }}>
          <div className="track-panel-title">
            <span>CUSTOMER FEEDBACK</span>
            <strong>How was your shipment experience?</strong>
          </div>
          {feedbackSent ? (
            <div className="empty">
              Thank you. Your feedback has been received by AAL.
            </div>
          ) : (
            <form
              onSubmit={async (event) => {
                event.preventDefault();
                try {
                  await publicTrackingApi.feedback(params.token, {
                    rating: feedbackRating,
                    category: "SHIPMENT_EXPERIENCE",
                    comment: feedbackComment.trim() || undefined,
                  });
                  setFeedbackSent(true);
                } catch (e) {
                  setError(
                    e instanceof ApiError
                      ? e.message
                      : "Unable to send feedback.",
                  );
                }
              }}
              style={{ display: "grid", gap: 10 }}
            >
              <select
                value={feedbackRating}
                onChange={(e) => setFeedbackRating(Number(e.target.value))}
              >
                {[5, 4, 3, 2, 1].map((value) => (
                  <option key={value} value={value}>
                    {value} / 5
                  </option>
                ))}
              </select>
              <textarea
                value={feedbackComment}
                onChange={(e) => setFeedbackComment(e.target.value)}
                placeholder="Tell AAL about your experience"
                rows={4}
              />
              <button className="public-main-button" type="submit">
                Send feedback
              </button>
            </form>
          )}
        </section>
        <div className="track-cta">
          <Link href="/track">Track another shipment</Link>
          <Link href="/">AAL home</Link>
        </div>
      </section>
    </main>
  );
}
function FlowNav() {
  return (
    <nav className="public-nav flow-nav">
      <Link href="/" className="public-brand">
        <span className="public-brand-mark">
          <Icon name="plane" size={20} />
        </span>
        <span>
          <strong>AAL</strong>
          <small>AFRICA LOGISTIC AVIATION</small>
        </span>
      </Link>
      <div className="flow-nav-links">
        <Link href="/quote">Quote</Link>
        <Link href="/book">Book</Link>
        <Link href="/track">Track</Link>
        <Link href="/login">AAL team</Link>
      </div>
    </nav>
  );
}

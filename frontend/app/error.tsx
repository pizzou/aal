"use client";

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main style={{minHeight:"100vh",display:"grid",placeItems:"center",fontFamily:"system-ui",padding:24}}>
      <section style={{maxWidth:520,textAlign:"center"}}>
        <h1>Something went wrong</h1>
        <p>We could not complete this page request. Your operational data has not been changed by this screen error.</p>
        <button onClick={() => reset()}>Try again</button>
      </section>
    </main>
  );
}

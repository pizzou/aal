"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { logisticsApi } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";

export default function MultimodalPage() {
  const { accessToken, isLoading } = useAuth(); const router = useRouter(); const [cap,setCap]=useState<Record<string,unknown>|null>(null); const [error,setError]=useState("");
  useEffect(()=>{if(!isLoading&&!accessToken)router.push("/login")},[isLoading,accessToken,router]);
  useEffect(()=>{if(accessToken)logisticsApi.capabilities().then(setCap).catch(e=>setError(e?.message||"Unable to load multimodal capabilities"))},[accessToken]);
  if(isLoading||!accessToken)return null;
  return <main className="page"><div className="page-head"><div><div className="eyebrow">MULTIMODAL LOGISTICS</div><h1 className="page-title">Mode Control</h1><p className="page-subtitle">Air, road, rail and ocean execution remain attached to one shipment and one operational timeline.</p></div><Link className="btn btn-primary" href="/shipments">Open shipment register</Link></div>{error&&<div className="alert alert-error">{error}</div>}<section className="grid grid-4" style={{marginTop:14}}>{[["AIR","/air-cargo","Flight capacity, booking, AWB and carrier submission"],["ROAD","/road","CMR, dispatch, vehicle, driver and delivery"],["RAIL","/rail","Consignment, train, wagons and terminals"],["OCEAN","/ocean","Voyage, booking, container, VGM and B/L workflow"]].map(([name,href,desc])=><Link className="card" href={href} key={name} style={{textDecoration:"none",color:"inherit"}}><div className="eyebrow">{name}</div><h2 className="card-title">{name} control desk</h2><p className="card-muted">{desc}</p></Link>)}</section><section className="card" style={{marginTop:14}}><h2 className="card-title">Capability boundary</h2><div className="card-muted">{cap?Object.entries(cap).map(([k,v])=><div className="metric-row" key={k}><span>{k}</span><strong>{String(v)}</strong></div>):"Loading capability registry…"}</div></section></main>;
}

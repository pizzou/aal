"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { aalBusinessApi, AalBusinessCockpit, ApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import { useRouter } from "next/navigation";

const money = (n: number, c: string) => `${c} ${n.toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2})}`;
const today = () => new Date().toISOString().slice(0,10);
const monthStart = () => { const d=new Date(); d.setDate(1); return d.toISOString().slice(0,10); };

export default function AalControlTower() {
  const {accessToken,isLoading}=useAuth();
  const router=useRouter();
  const [data,setData]=useState<AalBusinessCockpit|null>(null);
  const [from,setFrom]=useState(monthStart());
  const [to,setTo]=useState(today());
  const [currency,setCurrency]=useState("USD");
  const [error,setError]=useState("");

  useEffect(()=>{ if(!isLoading&&!accessToken) router.replace("/login"); },[isLoading,accessToken,router]);
  useEffect(()=>{ if(!accessToken)return; aalBusinessApi.cockpit(from,to,currency).then(setData).catch((e)=>setError(e instanceof ApiError?e.message:"Unable to load AAL cockpit")); },[accessToken,from,to,currency]);
  if(isLoading||!accessToken)return null;

  return <main className="page">
    <div className="page-head">
      <div><div className="eyebrow">AAL NATIVE BUSINESS ENGINE</div><h1 className="page-title">Operations & Profitability</h1><p className="page-subtitle">A single source of truth for the operating rules previously maintained manually.</p></div>
      <div className="actions"><Link className="btn btn-primary" href="/shipments">Open shipment register</Link></div>
    </div>
    <div className="card" style={{marginBottom:16}}><div className="grid grid-4">
      <label>From<input className="input" type="date" value={from} onChange={e=>setFrom(e.target.value)}/></label>
      <label>To<input className="input" type="date" value={to} onChange={e=>setTo(e.target.value)}/></label>
      <label>Reporting currency<select className="input" value={currency} onChange={e=>setCurrency(e.target.value)}><option>USD</option><option>RWF</option><option>EUR</option><option>GBP</option><option>CNY</option><option>AED</option></select></label>
      <div style={{display:"flex",alignItems:"end"}}><Link className="btn" href="/command-center">Historical migration</Link></div>
    </div></div>
    {error&&<div className="alert alert-error">{error}</div>}
    {!data?<div className="card">Loading native operating model…</div>:<>
      <section className="grid grid-4">
        <Kpi title="Shipments" value={data.shipments.toLocaleString()} meta={`${data.activeShipments} active · ${data.deliveredShipments} delivered`}/>
        <Kpi title="Chargeable weight" value={`${data.chargeableWeightKg.toLocaleString()} kg`} meta={`${data.grossWeightKg.toLocaleString()} kg gross`}/>
        <Kpi title="Revenue" value={money(data.billed,data.currency)} meta={`${money(data.collected,data.currency)} collected`}/>
        <Kpi title="Receivable" value={money(data.receivable,data.currency)} meta={`${data.overdueTasks} overdue tasks`}/>
      </section>
      <section className="grid grid-4" style={{marginTop:16}}>
        <Kpi title="Total cost" value={money(data.totalCost,data.currency)} meta="Supplier + other cost"/>
        <Kpi title="Gross profit" value={money(data.grossProfit,data.currency)} meta={`${data.marginPercent.toFixed(2)}% margin`}/>
        <Kpi title="Net income" value={money(data.netIncome,data.currency)} meta="Billed − supplier paid − other expenses"/>
        <Kpi title="Sales pipeline" value={`${data.openQuotes} open`} meta={`${data.wonQuotes} won`}/>
      </section>
      <section className="grid grid-2" style={{marginTop:16}}>
        <div className="card"><h2 className="card-title">Monthly operating performance</h2><div className="table-wrap"><table className="table"><thead><tr><th>Month</th><th>Shipments</th><th>Revenue</th><th>Collected</th><th>Cost</th><th>Gross profit</th></tr></thead><tbody>{data.monthly.map(m=><tr key={m.month}><td><strong>{m.month}</strong></td><td>{m.shipments}</td><td>{money(m.revenue,data.currency)}</td><td>{money(m.collected,data.currency)}</td><td>{money(m.cost,data.currency)}</td><td>{money(m.grossProfit,data.currency)}</td></tr>)}</tbody></table></div></div>
        <div className="card"><h2 className="card-title">Top lanes by revenue</h2><div className="table-wrap"><table className="table"><thead><tr><th>Lane</th><th>Jobs</th><th>Revenue</th><th>Profit</th><th>Delayed</th></tr></thead><tbody>{data.lanes.map((l,i)=><tr key={`${l.origin}-${l.destination}-${i}`}><td><strong>{l.origin} → {l.destination}</strong></td><td>{l.shipments}</td><td>{money(l.revenue,data.currency)}</td><td>{money(l.grossProfit,data.currency)}</td><td>{l.delayed}</td></tr>)}</tbody></table></div></div>
      </section>
      <section className="card" style={{marginTop:16}}><h2 className="card-title">Native formula controls</h2><div className="grid grid-3"><Rule name="Chargeable weight" formula="MAX(gross weight, volumetric weight)"/><Rule name="Total cost" formula="supplier cost + other cost"/><Rule name="Customer receivable" formula="MAX(billed − collected, 0)"/><Rule name="Gross profit" formula="billed − supplier cost − other cost"/><Rule name="Gross margin" formula="gross profit ÷ billed × 100"/><Rule name="Net income" formula="billed − supplier paid − other expenses"/></div></section>
    </>}
  </main>
}
function Kpi({title,value,meta}:{title:string,value:string,meta:string}){return <div className="card kpi"><div className="kpi-label">{title}</div><div className="kpi-value" style={{fontSize:22}}>{value}</div><div className="kpi-meta">{meta}</div></div>}
function Rule({name,formula}:{name:string,formula:string}){return <div className="card" style={{background:"var(--aal-panel-soft)"}}><div className="kpi-label">{name}</div><strong>{formula}</strong></div>}

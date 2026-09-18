"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ApiError, auditApi } from "@/lib/api-client";

export default function AuditPage(){
 const {accessToken,isLoading}=useAuth(); const router=useRouter(); const [rows,setRows]=useState<any[]>([]); const [error,setError]=useState("");
 useEffect(()=>{if(!isLoading&&!accessToken)router.push("/login")},[isLoading,accessToken,router]);
 useEffect(()=>{if(accessToken)auditApi.list().then(x=>setRows(x.content)).catch(e=>setError(e instanceof ApiError?e.message:"Unable to load audit trail"))},[accessToken]);
 if(isLoading||!accessToken)return null;
 return <main className="page"><div className="page-head"><div><div className="eyebrow">SECURITY & GOVERNANCE</div><h1 className="page-title">Audit Trail</h1><p className="page-subtitle">Tenant-scoped record of authenticated controller actions, outcome and request metadata. Secrets and request bodies are intentionally excluded.</p></div></div>{error&&<div className="alert alert-error">{error}</div>}<section className="card" style={{marginTop:14}}><div className="table-wrap"><table className="table"><thead><tr><th>Time</th><th>Action</th><th>Resource</th><th>Method / path</th><th>User</th><th>Result</th><th>IP</th></tr></thead><tbody>{rows.map(r=><tr key={r.id}><td>{new Date(r.createdAt).toLocaleString()}</td><td><strong>{r.action}</strong></td><td>{r.resourceType}{r.resourceId?<small>{r.resourceId}</small>:null}</td><td>{r.method}<small>{r.path}</small></td><td>{r.userId||"—"}</td><td><span className={r.success?"status status-success":"status status-danger"}>{r.statusCode||"—"}</span></td><td>{r.ipAddress||"—"}</td></tr>)}{!rows.length&&<tr><td colSpan={7} className="empty">No audit records.</td></tr>}</tbody></table></div></section></main>;
}

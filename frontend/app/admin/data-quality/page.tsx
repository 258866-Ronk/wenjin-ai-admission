"use client";
import{PageHeader}from"@/components/page-header";import{api}from"@/lib/api";import{useEffect,useState}from"react";
export default function DataQuality(){const[data,setData]=useState<Record<string,number>>();const[error,setError]=useState<string>();useEffect(()=>{void api.dataQuality().then(setData).catch(e=>setError(e instanceof Error?e.message:"读取失败"))},[]);return <div className="page-shell"><PageHeader/><main className="content-page"><h1>数据质量看板</h1><p className="muted">该页面仅管理员可访问。</p>{error&&<div className="notice">{error}；请使用 admin@wenjin.local / Admin123! 登录。</div>}{data&&<div className="metric-grid" style={{marginTop:24}}>{Object.entries(data).map(([key,value])=><div className="metric" key={key}><span>{key}</span><strong>{value}</strong></div>)}</div>}</main></div>}


"use client";

import { API_BASE, ApiError, api } from "@/lib/api";
import { useWorkspace } from "@/lib/store";
import type { Candidate, Conversation, Message, Report, Risk, User } from "@/lib/types";
import { AlertTriangle, BookOpenCheck, Bot, CheckCircle2, ChevronRight, CircleGauge, ClipboardList, Compass, Database, FileText, LoaderCircle, LogIn, LogOut, MessageSquarePlus, PanelRight, Send, Settings, ShieldCheck, Trash2, UserRound } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

const eventLabels:Record<string,string>={run_started:"咨询任务已创建",profile_analyzing:"正在分析考生资料",profile_question_required:"需要补充一项关键资料",data_resolving:"正在锁定数据版本",agents_parallel_started:"正在查询招生事实和来源",retrieval_completed:"招生数据检索完成",rules_completed:"硬规则校验完成",recommendation_started:"开始计算冲稳保",recommendation_completed:"候选分层完成",risk_check_started:"正在检查整表风险",risk_check_completed:"风险检查完成",report_generating:"正在生成结构化报告",self_check_round:"报告合规检查完成",degraded_notice:"解释服务已降级，数据与规则不受影响",report_completed:"咨询报告生成完成",run_failed:"任务执行失败"};
const quickQuestions=["我是河南物理类考生，2025年位次28000，想学计算机。","没有官方位次时，系统会怎样处理？","帮我解释冲、稳、保的区别。","中外合作项目填报时要注意什么？"];

// 服务启动期等待函数，页面会自动重试，无需用户手动刷新。
function waitForService(ms:number){return new Promise<void>(resolve=>window.setTimeout(resolve,ms))}

export function AdmissionWorkspace(){
  const router=useRouter();
  const state=useWorkspace();const set=state.set;const addProgress=state.addProgress;
  const[input,setInput]=useState("");const[loading,setLoading]=useState(true);const[sending,setSending]=useState(false);const[error,setError]=useState<string>();const eventSource=useRef<EventSource | null>(null);const monitorToken=useRef(0);
  const loadConversation=useCallback(async(id:string)=>{
    const detail=await api.conversation(id);
    let report:Report|undefined;
    const reportMessage=detail.messages.findLast(message=>message.messageType==="REPORT_READY"&&Boolean(message.structuredContentJson));
    if(reportMessage?.structuredContentJson){
      try{const reference=JSON.parse(reportMessage.structuredContentJson) as{reportId?:string};if(reference.reportId)report=await api.report(reference.reportId)}catch{}
    }
    set({activeId:id,messages:detail.messages,progress:[],report});
  },[set]);
  // 已登录用户进入工作台；未登录状态交给显式登录页处理。
  const bootstrap=useCallback(async()=>{
    const user=await api.me();
    let list=await api.conversations();
    if(list.length===0){const created=await api.createConversation();list=[created]}
    const profile=await api.profile();
    set({user,conversations:list,profile,activeId:list[0].id});
    await loadConversation(list[0].id);
  },[loadConversation,set]);

  // 首次启动时自动等待后端健康，401 则跳转登录，其他启动期错误自动重试。
  useEffect(()=>{
    let cancelled=false;
    const openWorkspace=async()=>{
      setLoading(true);setError(undefined);
      for(let attempt=0;attempt<15&&!cancelled;attempt++){
        try{
          await bootstrap();
          if(!cancelled)setLoading(false);
          return;
        }catch(cause){
          if(cause instanceof ApiError&&cause.status===401){
            router.replace("/login");
            return;
          }
          const retryable=cause instanceof ApiError&&[502,503,504].includes(cause.status);
          if(retryable&&attempt<14){
            if(!cancelled)setError(`服务正在启动，正在自动重试（${attempt+1}/15）…`);
            await waitForService(2000);
            continue;
          }
          if(!cancelled){setError(cause instanceof Error?cause.message:"系统初始化失败");setLoading(false)}
          return;
        }
      }
    };
    const timer=window.setTimeout(()=>void openWorkspace(),0);
    return()=>{cancelled=true;monitorToken.current+=1;window.clearTimeout(timer);eventSource.current?.close()};
  },[bootstrap,router]);

  const refreshConversation=useCallback(async(id:string)=>{const detail=await api.conversation(id);set({messages:detail.messages});},[set]);
  const connectEvents=useCallback((runId:string,conversationId:string)=>{
    eventSource.current?.close();
    const token=++monitorToken.current;
    const stream=new EventSource(`${API_BASE}/runs/${runId}/events`,{withCredentials:true});
    eventSource.current=stream;
    let terminal=false;
    const active=()=>monitorToken.current===token;
    const stop=()=>{terminal=true;if(active())monitorToken.current+=1;stream.close()};
    const finish=async(reportId:string)=>{
      if(terminal||!active())return;
      terminal=true;
      try{
        const report=await api.report(reportId);
        if(!active())return;
        set({report,mobileReport:true});
        await refreshConversation(conversationId);
      }catch(cause){if(active())setError(cause instanceof Error?cause.message:"报告读取失败")}
      finally{if(active())monitorToken.current+=1;stream.close()}
    };
    // SSE 被代理或网络瞬时打断时，持续轮询任务状态，避免界面停住却没有结果。
    const pollUntilTerminal=async()=>{
      addProgress("实时连接中断，正在自动查询任务状态");
      for(let attempt=0;attempt<160&&active()&&!terminal;attempt++){
        try{
          const run=await api.run(runId);
          if(run.state?.reportId){await finish(run.state.reportId);return}
          if(["FAILED","CANCELLED"].includes(run.run.status)){
            if(active())setError(run.run.errorMessage??"咨询任务执行失败");
            stop();return;
          }
        }catch{}
        await waitForService(1500);
      }
      if(active()&&!terminal){setError("任务处理时间较长，请稍后重新打开本次咨询查看结果");stop()}
    };
    for(const eventName of Object.keys(eventLabels)){
      stream.addEventListener(eventName,(raw)=>{
        const message=raw as MessageEvent<string>;let detail=eventLabels[eventName];
        try{
          const parsed=JSON.parse(message.data) as{data?:{message?:string;recordCount?:number;hardBlocked?:number;candidateCount?:number;reportId?:string}};
          if(parsed.data?.message)detail=parsed.data.message;
          if(parsed.data?.recordCount!==undefined)detail=`已检索 ${parsed.data.recordCount} 条招生记录`;
          if(parsed.data?.hardBlocked!==undefined)detail=`硬规则校验完成，排除 ${parsed.data.hardBlocked} 项`;
          if(parsed.data?.candidateCount!==undefined)detail=`已生成 ${parsed.data.candidateCount} 个可用候选`;
          if(eventName==="report_completed"&&parsed.data?.reportId)void finish(parsed.data.reportId);
        }catch{}
        addProgress(detail);
        if(eventName==="profile_question_required"){void refreshConversation(conversationId);stop()}
        if(eventName==="run_failed"){setError(detail);stop()}
      });
    }
    stream.onerror=()=>{if(!terminal&&active()){stream.close();void pollUntilTerminal()}};
  },[addProgress,refreshConversation,set]);

  const send=useCallback(async(text?:string)=>{const content=(text??input).trim();if(!content||!state.activeId||sending)return;setSending(true);setError(undefined);setInput("");const optimistic:Message={id:crypto.randomUUID(),role:"user",messageType:"TEXT",content,createdAt:new Date().toISOString()};set({messages:[...state.messages,optimistic],progress:[],report:undefined,mobileReport:false});try{const submitted=await api.send(state.activeId,content);connectEvents(submitted.runId,state.activeId);const profile=await api.profile();set({profile})}catch(e){setError(e instanceof Error?e.message:"消息发送失败")}finally{setSending(false)}},[connectEvents,input,sending,set,state.activeId,state.messages]);
  const newConversation=async()=>{const created=await api.createConversation();set({conversations:[created,...state.conversations]});await loadConversation(created.id)};
  const removeConversation=async(id:string)=>{await api.deleteConversation(id);const remaining=state.conversations.filter(c=>c.id!==id);if(remaining.length===0){const created=await api.createConversation();remaining.push(created)}set({conversations:remaining});await loadConversation(remaining[0].id)};
  if(loading)return <div className="login-wrap"><div className="service-startup"><div className="brand"><span className="brand-mark"><Compass/></span>问津智愿 AI</div><LoaderCircle className="animate-spin" size={28}/><strong>{error??"正在连接服务…"}</strong><span className="muted">页面会自动重试，无需手动刷新。</span></div></div>;
  return <div><Header user={state.user}/><main className={`workspace ${state.mobileReport?"show-report":""}`}><Sidebar conversations={state.conversations} activeId={state.activeId} onSelect={loadConversation} onNew={newConversation} onDelete={removeConversation} isAdmin={state.user?.role==="ADMIN"}/><ChatPane messages={state.messages} progress={state.progress} input={input} onInput={setInput} onSend={send} sending={sending} error={error} onShowReport={()=>set({mobileReport:true})}/><ReportPane report={state.report} profile={state.profile} onShowChat={()=>set({mobileReport:false})}/></main></div>;
}

function Header({user}:{user?:User}){
  const router=useRouter();const[loggingOut,setLoggingOut]=useState(false);
  // 退出时清空前端工作区状态并返回登录页。
  const logout=async()=>{setLoggingOut(true);try{await api.logout()}finally{useWorkspace.getState().set({user:undefined,conversations:[],activeId:undefined,messages:[],profile:undefined,report:undefined,progress:[]});router.replace("/login");router.refresh()}};
  return <header className="app-header"><Link href="/" className="brand" style={{textDecoration:"none"}}><span className="brand-mark"><Compass size={20}/></span><span>问津智愿 AI</span></Link><nav className="header-actions"><Link className="button" href="/reports"><FileText size={16}/>报告</Link><Link className="button" href="/profile"><UserRound size={16}/>{user?.displayName??"档案"}</Link>{user?.anonymous?<Link className="button primary" href="/login"><LogIn size={16}/>登录</Link>:<button className="button" disabled={loggingOut} onClick={()=>void logout()}><LogOut size={16}/>{loggingOut?"退出中…":"退出"}</button>}</nav></header>}
function Sidebar({conversations,activeId,onSelect,onNew,onDelete,isAdmin}:{conversations:Conversation[];activeId?:string;onSelect:(id:string)=>Promise<void>;onNew:()=>Promise<void>;onDelete:(id:string)=>Promise<void>;isAdmin:boolean}){return <aside className="sidebar"><button className="button primary" style={{width:"100%",justifyContent:"center"}} onClick={()=>void onNew()}><MessageSquarePlus size={17}/>新建咨询</button><div className="sidebar-title">历史咨询</div>{conversations.map(c=><div key={c.id} style={{position:"relative"}}><button className={`conversation-row ${activeId===c.id?"active":""}`} onClick={()=>void onSelect(c.id)}><strong>{c.title}</strong><small>{new Date(c.updatedAt).toLocaleDateString("zh-CN")}</small></button>{activeId===c.id&&<button aria-label="删除会话" className="button icon danger" style={{position:"absolute",right:4,top:6,border:0,background:"transparent"}} onClick={()=>void onDelete(c.id)}><Trash2 size={14}/></button>}</div>)}{isAdmin&&<><div className="sidebar-title">管理工具</div><Link className="conversation-row" href="/admin/model-settings" style={{display:"block",textDecoration:"none"}}><strong><Settings size={15} style={{display:"inline",marginRight:7}}/>模型 API</strong></Link><Link className="conversation-row" href="/admin/imports" style={{display:"block",textDecoration:"none"}}><strong><Database size={15} style={{display:"inline",marginRight:7}}/>数据导入</strong></Link><Link className="conversation-row" href="/admin/data-quality" style={{display:"block",textDecoration:"none"}}><strong><CircleGauge size={15} style={{display:"inline",marginRight:7}}/>数据质量</strong></Link></>}</aside>}

function ChatPane({messages,progress,input,onInput,onSend,sending,error,onShowReport}:{messages:Message[];progress:string[];input:string;onInput:(v:string)=>void;onSend:(v?:string)=>Promise<void>;sending:boolean;error?:string;onShowReport:()=>void}){const bottom=useRef<HTMLDivElement>(null);useEffect(()=>{bottom.current?.scrollIntoView({behavior:"smooth"});},[messages,progress]);return <section className="chat-pane"><div className="pane-head"><div><h1>AI 志愿咨询</h1><span className="muted" style={{fontSize:12}}>结构化数据与规则引擎给出结论</span></div><div className="mobile-tabs"><button className="button primary"><Bot size={15}/>对话</button><button className="button" onClick={onShowReport}><PanelRight size={15}/>报告</button></div></div><div className="chat-scroll">{messages.length===0?<div className="welcome"><BookOpenCheck size={34} color="#1e40af"/><h2>把考生情况告诉我，我们一步一步来</h2><p>我会先完成建档，再查询实际招生数据、校验硬规则并生成可解释的冲稳保方案。信息不足时，每轮只追问一个关键问题。</p><div className="quick-grid">{quickQuestions.map(q=><button className="quick" key={q} onClick={()=>void onSend(q)}>{q}<ChevronRight size={15} style={{float:"right"}}/></button>)}</div></div>:messages.map(m=><div className={`message ${m.role}`} key={m.id}><div>{m.content}</div><div className="message-meta">{new Date(m.createdAt).toLocaleTimeString("zh-CN",{hour:"2-digit",minute:"2-digit"})}</div></div>)}{progress.length>0&&<div className="progress"><strong style={{fontSize:13}}>任务进度</strong>{progress.map((line,index)=><div className={`progress-line ${index<progress.length-1?"done":""}`} key={`${line}-${index}`}>{index===progress.length-1&&sending?<LoaderCircle size={16} className="animate-spin"/>:<CheckCircle2 size={16}/>}<span>{line}</span></div>)}</div>}{error&&<div className="notice" style={{borderColor:"#dc2626",background:"#fef2f2",color:"#991b1b"}}>{error}</div>}<div ref={bottom}/></div><div className="composer"><div className="composer-box"><textarea value={input} onChange={e=>onInput(e.target.value)} placeholder="例如：河南物理类，2025年位次28000，想学计算机……" onKeyDown={e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();void onSend()}}}/><button className="button primary icon" aria-label="发送" disabled={sending||!input.trim()} onClick={()=>void onSend()}>{sending?<LoaderCircle size={19}/>:<Send size={19}/>}</button></div><div className="muted" style={{fontSize:11,marginTop:7}}>系统只提供辅助分析，正式填报前请核对考试院和学校官网。</div></div></section>}

function ReportPane({report,profile,onShowChat}:{report?:Report;profile?:import("@/lib/types").Profile;onShowChat:()=>void}){const completeness=[profile?.province,profile?.examYear,profile?.score??profile?.rank,profile?.subjectType,profile?.batch].filter(Boolean).length*20;return <aside className="report-pane"><div className="report-head"><div className="report-head-top"><div><strong>实时报告画布</strong><div className="muted" style={{fontSize:12}}>{report?`数据版本 ${report.datasetVersion}`:"等待完成考生建档"}</div></div><div className="mobile-tabs"><button className="button" onClick={onShowChat}><Bot size={15}/>对话</button><button className="button primary"><PanelRight size={15}/>报告</button></div></div></div><div className="report-body"><section className="section"><div style={{display:"flex",justifyContent:"space-between",fontSize:13,marginBottom:7}}><span>档案完成度</span><strong>{completeness}%</strong></div><div className="profile-progress"><span style={{width:`${completeness}%`}}/></div>{profile&&<p className="muted" style={{fontSize:12}}>{[profile.province,profile.examYear&&`${profile.examYear}年`,profile.subjectType,profile.rank&&`位次 ${profile.rank}`,profile.score&&`${profile.score}分`].filter(Boolean).join(" · ")}</p>}</section>{!report?<div className="empty"><ClipboardList size={34}/><p>发送考生资料后，这里会实时出现数据覆盖、风险画像和推荐结果。</p></div>:<><section className="section"><h2>冲稳保概览</h2><div className="metric-grid"><div className="metric challenge"><span>冲</span><strong>{report.tierSummary.challenge}</strong></div><div className="metric stable"><span>稳</span><strong>{report.tierSummary.stable}</strong></div><div className="metric safe"><span>保</span><strong>{report.tierSummary.safe}</strong></div></div><div className="notice">{report.dataCoverage}</div>{report.explanationDegraded&&<div className="notice">当前使用本地解释模板；结构化数据、硬规则和分层结果不受影响。</div>}<p style={{lineHeight:1.65}}>{report.overallComment}</p></section><section className="section"><h2>均衡型候选</h2>{report.balancedPlan.items.map(item=><CandidateRow item={item} key={item.record.id}/>)}</section><section className="section"><h2>方案风险</h2>{report.riskItems.map(risk=><RiskRow risk={risk} key={risk.code}/>)}</section><section className="section"><h2>证据与来源</h2>{report.evidence.slice(0,8).map(e=><div key={e.id} style={{padding:"9px 0"}}><strong style={{fontSize:13}}>{e.title}</strong><p className="muted" style={{fontSize:12,margin:"4px 0"}}>{e.excerpt}</p><Link className="source-link" href={`/sources/${encodeURIComponent(e.sourceId)}`}>查看来源与边界</Link></div>)}</section><section className="section"><ShieldCheck size={19} color="#0d9488"/><p className="muted" style={{fontSize:12,lineHeight:1.65}}>{report.disclaimer}</p></section></>}</div></aside>}
function CandidateRow({item}:{item:Candidate}){const group=item.tierGroup==="冲"?"challenge":item.tierGroup==="稳"?"stable":"safe";return <div className="candidate"><div className="candidate-top"><div><h3>{item.record.schoolName}</h3><div className="candidate-major">{item.record.majorName??item.record.majorGroup??"院校投档记录"}</div></div><span className={`tier ${group}`}>{item.tier}</span></div><div>{item.record.schoolTags.slice(0,3).map(tag=><span className="tag" key={tag}>{tag}</span>)}</div><div className="candidate-data"><span>城市<strong>{item.record.city||"未公开"}</strong></span><span>参考位次<strong>{item.record.minRankEnd?.toLocaleString("zh-CN")??"原始来源未公开"}</strong></span><span>参考分数<strong>{item.record.minScore??"未公开"}</strong></span><span>参考年份<strong>{item.record.year}</strong></span><span>招生计划<strong>{item.record.planCount??"待核验"}</strong></span><span>学费<strong>{item.record.tuition?`${item.record.tuition} 元`:"待核验"}</strong></span></div><p className="muted" style={{fontSize:12,lineHeight:1.6}}>{item.reasons.join("；")}</p><Link className="source-link" href={`/sources/${encodeURIComponent(item.record.sourceId)}`}>查看数据来源</Link></div>}
function RiskRow({risk}:{risk:Risk}){return <div className={`risk ${risk.level}`}><AlertTriangle size={18}/><div><strong style={{fontSize:13}}>{risk.title}</strong><p>{risk.detail}</p></div></div>}

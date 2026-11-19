"use client";

import { PageHeader } from "@/components/page-header";
import { api } from "@/lib/api";
import type { AiConfig, AiConfigTest, AiConfigUpdate } from "@/lib/types";
import { CheckCircle2, Eye, EyeOff, LoaderCircle, PlugZap, Save, ShieldCheck, XCircle } from "lucide-react";
import { useEffect, useState } from "react";

const defaults: AiConfigUpdate = {
  mode: "mock", provider: "openai-compatible", baseUrl: "https://api.openai.com/v1", apiKey: "",
  clearApiKey: false, chatModel: "", fastModel: "", embeddingModel: "", timeoutSeconds: 60,
  maxRetries: 2, maxOutputTokens: 2048,
};

export default function ModelSettingsPage() {
  const [form, setForm] = useState<AiConfigUpdate>(defaults);
  const [saved, setSaved] = useState<AiConfig>();
  const [test, setTest] = useState<AiConfigTest>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [showKey, setShowKey] = useState(false);
  const [error, setError] = useState<string>();

  const apply = (config: AiConfig) => {
    setSaved(config);
    setForm({mode:config.mode,provider:config.provider,baseUrl:config.baseUrl,apiKey:"",clearApiKey:false,
      chatModel:config.chatModel??"",fastModel:config.fastModel??"",embeddingModel:config.embeddingModel??"",
      timeoutSeconds:config.timeoutSeconds,maxRetries:config.maxRetries,maxOutputTokens:config.maxOutputTokens});
  };

  useEffect(() => { void api.aiConfig().then(apply).catch(e => setError(e instanceof Error ? e.message : "读取配置失败"))
    .finally(() => setLoading(false)); }, []);

  const field = <K extends keyof AiConfigUpdate>(key: K, value: AiConfigUpdate[K]) => setForm(v => ({...v,[key]:value}));
  const save = async () => { setSaving(true);setError(undefined);setTest(undefined);try{apply(await api.saveAiConfig(form))}
    catch(e){setError(e instanceof Error?e.message:"保存失败")}finally{setSaving(false)} };
  const testConnection = async () => {setTesting(true);setError(undefined);try{setTest(await api.testAiConfig())}
    catch(e){setError(e instanceof Error?e.message:"测试失败")}finally{setTesting(false)} };

  return <div className="page-shell"><PageHeader/><main className="content-page">
    <h1>大模型 API 配置</h1><p className="muted">管理员可在这里切换模型服务。保存后立即生效，无需重启前后端。</p>
    {error&&<div className="notice" style={{borderColor:"#dc2626",background:"#fef2f2",color:"#991b1b"}}>{error}；请确认使用管理员账号登录。</div>}
    {loading?<div className="form-grid"><LoaderCircle className="animate-spin"/>正在读取配置…</div>:<>
      <div className="notice"><ShieldCheck size={18} style={{verticalAlign:"middle",marginRight:8}}/>API Key 只发送给 Java 后端，页面不会读取或回显完整 Key；数据库仅保存 AES-GCM 密文。</div>
      <div className="notice">模型 API 只负责生成文字解释，不负责提供学校名单。院校、专业和冲稳保结果来自本地招生数据库；某省数据未覆盖时，即使 API 测试成功也不会生成虚构院校。</div>
      <div className="form-grid">
        <div className="field"><label>运行模式</label><select value={form.mode} onChange={e=>field("mode",e.target.value)}><option value="mock">Mock（无需外部 API）</option><option value="openai-compatible">OpenAI-compatible API</option></select></div>
        <div className="field"><label>接口协议</label><select value={form.provider} onChange={e=>field("provider",e.target.value)}><option value="openai-compatible">OpenAI Compatible</option></select></div>
        <div className="field full"><label>API Base URL</label><input value={form.baseUrl} onChange={e=>field("baseUrl",e.target.value)} placeholder="https://api.openai.com/v1"/><small className="muted">填写到版本根路径，系统会请求 `/chat/completions`。</small></div>
        <div className="field full"><label>API Key</label><div className="secret-input"><input type={showKey?"text":"password"} value={form.apiKey??""} onChange={e=>field("apiKey",e.target.value)} placeholder={saved?.apiKeyConfigured?`已配置 ${saved.apiKeyMasked}；留空表示保持不变`:"请输入 API Key"}/><button type="button" className="button icon" onClick={()=>setShowKey(v=>!v)} aria-label="显示或隐藏 API Key">{showKey?<EyeOff size={17}/>:<Eye size={17}/>}</button></div>
          {saved?.apiKeyConfigured&&<label className="check-row"><input type="checkbox" checked={form.clearApiKey} onChange={e=>field("clearApiKey",e.target.checked)}/>清除已保存的 API Key</label>}</div>
        <div className="field"><label>对话模型 *</label><input value={form.chatModel??""} onChange={e=>field("chatModel",e.target.value)} placeholder="例如 gpt-5-mini"/></div>
        <div className="field"><label>快速模型</label><input value={form.fastModel??""} onChange={e=>field("fastModel",e.target.value)} placeholder="可与对话模型相同"/></div>
        <div className="field"><label>向量模型</label><input value={form.embeddingModel??""} onChange={e=>field("embeddingModel",e.target.value)} placeholder="暂未启用时可留空"/></div>
        <div className="field"><label>请求超时（秒）</label><input type="number" min={1} max={300} value={form.timeoutSeconds} onChange={e=>field("timeoutSeconds",Number(e.target.value))}/></div>
        <div className="field"><label>失败重试次数</label><input type="number" min={0} max={5} value={form.maxRetries} onChange={e=>field("maxRetries",Number(e.target.value))}/></div>
        <div className="field"><label>最大输出 Token</label><input type="number" min={128} max={32768} value={form.maxOutputTokens} onChange={e=>field("maxOutputTokens",Number(e.target.value))}/></div>
        <div className="field full config-actions"><button className="button primary" disabled={saving} onClick={()=>void save()}>{saving?<LoaderCircle size={17} className="animate-spin"/>:<Save size={17}/>}保存并立即应用</button><button className="button" disabled={testing} onClick={()=>void testConnection()}>{testing?<LoaderCircle size={17} className="animate-spin"/>:<PlugZap size={17}/>}测试当前配置</button></div>
      </div>
      <div className="config-status"><strong>当前状态</strong><span>来源：{saved?.source==="FRONTEND"?"网页配置":"环境变量"}</span><span>模式：{saved?.mode}</span><span>Key：{saved?.apiKeyConfigured?saved.apiKeyMasked:"未配置"}</span>{saved?.updatedAt&&<span>更新时间：{new Date(saved.updatedAt).toLocaleString("zh-CN")}</span>}</div>
      {test&&<div className="notice" style={{borderColor:test.success?"#16a34a":"#dc2626",background:test.success?"#f0fdf4":"#fef2f2",color:test.success?"#166534":"#991b1b"}}>{test.success?<CheckCircle2 size={18}/>:<XCircle size={18}/>}<strong>{test.message}</strong><span> · {test.model||test.mode} · {test.latencyMillis} ms</span></div>}
    </>}
  </main></div>;
}

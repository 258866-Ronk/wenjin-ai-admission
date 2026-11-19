"use client";

import { api } from "@/lib/api";
import { zodResolver } from "@hookform/resolvers/zod";
import { Compass, LogIn, UserRound, UserPlus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";

const schema = z.object({
  email: z.email("请输入有效邮箱"),
  password: z.string().min(8, "密码至少 8 位"),
  displayName: z.string().max(80, "昵称最多 80 个字符").optional(),
});
type Form = z.infer<typeof schema>;

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [error, setError] = useState<string>();
  const [guestLoading, setGuestLoading] = useState(false);
  const {register, handleSubmit, formState: {errors, isSubmitting}} = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: {email: "demo@wenjin.local", password: "Demo123!", displayName: ""},
  });

  // 登录或注册成功后回到咨询工作台，并刷新服务端路由缓存。
  const submit = async (value: Form) => {
    setError(undefined);
    try {
      if (mode === "register") {
        const displayName = value.displayName?.trim();
        if (!displayName) {
          setError("注册时请输入昵称");
          return;
        }
        await api.register(value.email, value.password, displayName);
      } else {
        await api.login(value.email, value.password);
      }
      router.replace("/");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : mode === "login" ? "登录失败" : "注册失败");
    }
  };

  // 游客模式由用户主动选择，不再在首页静默创建游客账号。
  const enterAsGuest = async () => {
    setGuestLoading(true);
    setError(undefined);
    try {
      await api.anonymous();
      router.replace("/");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "游客模式启动失败");
    } finally {
      setGuestLoading(false);
    }
  };

  const busy = isSubmitting || guestLoading;
  return <main className="login-wrap"><section className="login-panel auth-panel">
    <div className="brand"><span className="brand-mark"><Compass/></span>问津智愿 AI</div>
    <h1>{mode === "login" ? "登录咨询工作台" : "创建咨询账号"}</h1>
    <p className="muted">登录后可长期保存档案、咨询记录和志愿报告。</p>

    <div className="auth-tabs" role="tablist" aria-label="账号入口">
      <button type="button" className={`auth-tab ${mode === "login" ? "active" : ""}`} onClick={() => {setMode("login"); setError(undefined);}}><LogIn size={16}/>登录</button>
      <button type="button" className={`auth-tab ${mode === "register" ? "active" : ""}`} onClick={() => {setMode("register"); setError(undefined);}}><UserPlus size={16}/>注册</button>
    </div>

    <form onSubmit={handleSubmit(submit)}>
      {mode === "register" && <div className="field"><label>昵称</label><input autoComplete="name" {...register("displayName")}/>{errors.displayName && <span className="error">{errors.displayName.message}</span>}</div>}
      <div className="field"><label>邮箱</label><input autoComplete="email" {...register("email")}/>{errors.email && <span className="error">{errors.email.message}</span>}</div>
      <div className="field"><label>密码</label><input type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} {...register("password")}/>{errors.password && <span className="error">{errors.password.message}</span>}</div>
      {error && <p className="error">{error}</p>}
      <button className="button primary" disabled={busy}>{isSubmitting ? mode === "login" ? "登录中…" : "注册中…" : mode === "login" ? "登录" : "注册并进入"}</button>
    </form>

    <div className="auth-divider"><span>或者</span></div>
    <button type="button" className="button auth-guest" disabled={busy} onClick={() => void enterAsGuest()}><UserRound size={17}/>{guestLoading ? "正在进入…" : "游客体验"}</button>
    <p className="muted auth-demo">演示账号：demo@wenjin.local / Demo123!</p>
  </section></main>;
}

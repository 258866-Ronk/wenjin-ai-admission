import {create} from "zustand";import type{Conversation,Message,Profile,Report,User}from"./types";
interface State{user?:User;conversations:Conversation[];activeId?:string;messages:Message[];profile?:Profile;report?:Report;progress:string[];mobileReport:boolean;set:(patch:Partial<State>)=>void;addProgress:(line:string)=>void}
export const useWorkspace=create<State>((set)=>({conversations:[],messages:[],progress:[],mobileReport:false,set:(patch)=>set(patch),addProgress:(line)=>set(s=>({progress:[...s.progress,line].slice(-20)}))}));


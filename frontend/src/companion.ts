export interface Appearance { theme:number; outfit:number; slots:number[] }
export interface CompanionMemory { sequence:number; at:number; kind:number; detail:number; theme:number; outfit:number }
export interface CompanionSnapshot {
  appearanceRevision:number; observedAt:number; owned:number; eligible:number; themes:number; writable:boolean;
  appearance:Appearance; best:number[]; played:number[]; focusCompleted:number; memories:CompanionMemory[];
}
export interface AppearanceRequest { requestId:string; expectedRevision:number; status:string; lastError:string; createdAt:string }
export interface CompanionSpace { enabled:boolean; online:boolean; fresh:boolean; name:string; snapshot:CompanionSnapshot|null; requests:AppearanceRequest[] }
export const themes=['轨道休息舱','月面温室','深空观测室'];
export const themeRules=['初始主题','等级 3 或完成一次专注','完成五局游戏'];
export const catalog=[
  {id:1,name:'轨道围巾',rule:'30 金币'}, {id:2,name:'星环耳饰',rule:'等级 2 · 60 金币'},
  {id:3,name:'小邮差包',rule:'完成接星屑'}, {id:4,name:'观测镜',rule:'轨道对接 60 分'},
  {id:5,name:'陪伴耳机',rule:'完成一次专注'}, {id:6,name:'星光天线',rule:'亲密度 20'},
  {id:7,name:'月球灯',rule:'30 金币'}, {id:8,name:'迷你盆栽',rule:'等级 2 · 45 金币'},
  {id:9,name:'星图框',rule:'信号接力 100 分'}, {id:10,name:'小火箭',rule:'完成五局游戏'},
  {id:11,name:'星屑奖杯',rule:'接星屑 100 分'}, {id:12,name:'陪伴手账',rule:'完成三次专注'},
];
export const memoryNames=['','第一次照料','成为亲密伙伴','成长新阶段','第一次游戏','刷新个人纪录','一起完成专注','获得新收藏','布置新小舱'];
export const gameNames=['接星屑','信号接力','轨道对接'];
export const requestNames:Record<string,string>={pending:'等待发送',sent:'等待设备保存',unconfirmed:'结果待核对',applied:'设备已保存',rejected:'设备未接受',expired:'请求已过期'};
export function copyAppearance(a:Appearance):Appearance{return {theme:a.theme,outfit:a.outfit,slots:[...a.slots]};}
export function owns(owned:number,id:number){return id===0 || Boolean(owned & (1 << (id-1)));}
export function appearanceAllowed(a:Appearance,s:CompanionSnapshot){
  return Boolean(s.themes & (1<<a.theme)) && owns(s.owned,a.outfit) && a.slots.every(id=>owns(s.owned,id)) && new Set(a.slots.filter(Boolean)).size===a.slots.filter(Boolean).length;
}
export function sameAppearance(a:Appearance,b:Appearance){return a.theme===b.theme&&a.outfit===b.outfit&&a.slots.every((v,i)=>v===b.slots[i]);}

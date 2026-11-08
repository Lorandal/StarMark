import {changePackage,walk} from './editor-state.js';
export const THEMES=[{id:'white',label:'白色',color:'#fff'},{id:'black',label:'黑色',color:'#151515'},{id:'blur-light',label:'浅色模糊',color:'linear-gradient(135deg,#d8e4b8,#b6cbcb,#ece0ca)'},{id:'blur-dark',label:'深色模糊',color:'linear-gradient(135deg,#3d5943,#496975,#343a31)'}];
export function applyTheme(pkg,id){return changePackage(pkg,s=>{
 if(s.authoring?.profile!=='authoring.ten-elements.v1'||s.scene?.canvas.mode!=='frame')throw Error('此模板没有可编辑边框');
 if(s.controls.some(c=>c.targets.some(t=>t.kind==='canvas'&&t.property==='background')))throw Error('背景由模板声明的款式控制，请使用对应选项');
 const dark=id==='black'||id==='blur-dark',color=dark?'#FFFFFFFF':'#141414FF';
 if(id.startsWith('blur')){s.scene.materials||={};s.scene.materials['web-frame-blur']={kind:'photo-blur',version:1,theme:dark?'dark':'light',blurSigmaU:12,scale:1.02,tint:dark?'#101810FF':'#FFFFFFFF',tintOpacity:dark?.5:.65,highlightColor:dark?'#FFFFFF20':'#FFFFFF80',highlightWidthU:1};s.scene.canvas.background={kind:'material',materialId:'web-frame-blur'};if(!s.requires.includes('material.photo-blur.v1'))s.requires.push('material.photo-blur.v1');}
 else s.scene.canvas.background={kind:'solid',color:dark?'#141414FF':'#FFFFFFFF'};
 walk(s.scene.nodes,n=>{for(const style of[n.textStyle,n.replacementStyle?.textStyle])if(style)style.color=color.slice(0,7)+(style.color?.slice(7)||'FF');if(n.type==='model'){const key=dark?'models-white':'models-black';if(s.scene.catalogs[key])n.catalogId=key;}});
 s.extensions||={};s.extensions['com.realize.starmark.web']={...s.extensions['com.realize.starmark.web'],theme:id};
});}

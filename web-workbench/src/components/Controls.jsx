import React,{useRef,useState,useEffect} from 'react';
import {Upload,RotateCcw} from 'lucide-react';
import {activeControls,root,setControl} from '../editor-state.js';
import {attachImage} from '../media.js';

export function NumberField({label,value,onChange,min=-1000000,max=1000000,step=1,suffix,disabled}) {
 const [draft,setDraft]=useState(String(value??''));useEffect(()=>setDraft(String(value??'')),[value]);
 const commit=()=>{const n=Number(draft);if(draft!==''&&Number.isFinite(n)&&n>=min&&n<=max&&n!==value)onChange(n);else setDraft(String(value??''));};
 return <label className="wb-number"><span>{label}</span><span className="wb-number-box"><input aria-label={label} type="number" value={draft} min={min} max={max} step={step} disabled={disabled} onChange={e=>setDraft(e.target.value)} onBlur={commit} onKeyDown={e=>{if(e.key==='Enter')e.currentTarget.blur()}}/>{suffix&&<small>{suffix}</small>}</span></label>;
}
export function TextField({label,value,onChange,multiline=false,placeholder,maxLength=500}) {
 const [text,setText]=useState(value??'');useEffect(()=>setText(value??''),[value]);const Tag=multiline?'textarea':'input';
 return <label className="wb-field"><span>{label}</span><Tag value={text} placeholder={placeholder} maxLength={maxLength} rows={3} onChange={e=>setText(e.target.value)} onBlur={()=>text!==value&&onChange(text)} onKeyDown={e=>{if(e.key==='Enter'&&!multiline)e.currentTarget.blur()}}/></label>;
}
function ResourceControl({pkg,control,commit,notify,onBusy}){
 const input=useRef();const s=root(pkg),value=s.defaults[control.id]||{mode:'native'};const isRegion=control.type==='region';const allowUpload=!isRegion||control.allowModes.includes('image');
 const save=v=>{try{commit(setControl(pkg,control.id,v));}catch(e){notify(e.message);}};
 const fonts=s.scene?.fonts||s.xiaomi?.fonts||{};
 const marks=Object.entries(s.resources).filter(([id,r])=>r.kind==='image'&&/(?:markc|marks)/i.test(id+' '+r.path));
 async function upload(file){if(!file||!allowUpload)return;onBusy?.('正在校验图片素材');try{const result=await attachImage(pkg,file,{allowTypes:control.allowTypes});commit(setControl(result.pkg,control.id,isRegion?{mode:'image',resourceId:result.id,fit:control.allowFits?.[0]||'contain'}:{mode:'image',resourceId:result.id,shape:control.allowShapes?.[0]||'rect'}));}catch(e){notify(e.message);}finally{input.current.value='';onBusy?.('');}}
 return <div className="wb-resource-control"><label className="wb-field"><span>{control.label}</span><select aria-label={control.label} value={isRegion?value.mode:(value.mode==='native'?'native':marks.some(([id])=>id===value.resourceId)?value.resourceId:'uploaded')} onChange={e=>{const id=e.target.value;if(isRegion){if(id==='image'){input.current.click();return;}save(id==='native'?{mode:'native'}:{mode:'text',text:'你的签名',fontId:Object.keys(fonts)[0]});}else if(id==='native')save({mode:'native'});else if(id==='uploaded')input.current.click();else save({mode:'image',resourceId:id,shape:(control.allowShapes||['rect']).includes('circle')&&/mark.?c/i.test(id+' '+s.resources[id]?.path)?'circle':control.allowShapes?.[0]||'rect'});}}>
 {isRegion?<><option value="native">原始机型字标</option>{control.allowModes.includes('text')&&<option value="text">文字签名</option>}{control.allowModes.includes('image')&&<option value="image">图片签名</option>}</>:<><option value="native">模板默认图标</option>{marks.map(([id,r])=><option key={id} value={id}>{/markc/i.test(id+' '+r.path)?'MarkC · 圆形':'MarkS · 方形'}</option>)}<option value="uploaded">自定义图标</option></>}
 </select></label>
 {isRegion&&value.mode==='text'&&<><TextField label="签名内容" value={value.text} onChange={text=>save({...value,text})}/><label className="wb-field"><span>签名字体</span><select aria-label="签名字体" value={value.fontId} onChange={e=>save({...value,fontId:e.target.value})}>{Object.keys(fonts).map(id=><option key={id} value={id}>{fontName(pkg,id)}</option>)}</select></label></>}
 {value.mode==='image'&&<label className="wb-field"><span>{isRegion?'图片适应':'图标形状'}</span><select aria-label={isRegion?'图片适应':'图标形状'} value={isRegion?value.fit:value.shape} onChange={e=>save({...value,[isRegion?'fit':'shape']:e.target.value})}>{(isRegion?(control.allowFits||['contain','cover']):(control.allowShapes||['rect','circle'])).map(v=><option key={v} value={v}>{{contain:'完整显示',cover:'填满并裁切',rect:'原图轮廓',circle:'圆形裁切'}[v]}</option>)}</select></label>}
 <div className="wb-control-actions">{allowUpload&&<button onClick={()=>input.current.click()}><Upload size={14}/>{isRegion?'上传图片签名':'上传图标'}</button>}{value.mode!=='native'&&<button aria-label={`恢复${control.label}`} title="恢复模板原始内容" onClick={()=>save({mode:'native'})}><RotateCcw size={14}/></button>}</div><input ref={input} hidden type="file" accept={(control.allowTypes||[]).join(",")} onChange={e=>upload(e.target.files[0])}/>
 </div>;
}
export function fontName(pkg,id){const s=root(pkg);const f=(s.scene?.fonts||s.xiaomi?.fonts||{})[id];const path=s.resources[f?.resourceId]?.path||id;const filename=path.split('/').pop().replace(/\.[^.]+$/,'');return /misans/i.test(filename)?'MiSans':/oxanium/i.test(filename)?'Oxanium':/lg1056/i.test(filename)?'LG1056':filename;}
export default function Controls({pkg,commit,notify,onBusy}){
 const s=root(pkg);const controls=activeControls(pkg).filter(c=>c.scope==='template');
 const set=(c,v)=>{try{commit(setControl(pkg,c.id,v));}catch(e){notify(e.message)}};
 return <div className="wb-controls">{controls.length===0?<p className="wb-note">选择画布中的元素，在属性中调整设计。</p>:controls.map(c=>{
 const value=s.defaults[c.id];
 if(c.type==='toggle')return <label className="wb-switch" key={c.id}><span>{c.label}</span><input type="checkbox" checked={!!value} onChange={e=>set(c,e.target.checked)}/></label>;
 if(c.type==='choice')return <label className="wb-field" key={c.id}><span>{c.label}</span><select aria-label={c.label} value={value} onChange={e=>set(c,e.target.value)}>{c.options.map(o=><option key={o.id} value={o.id}>{o.label}</option>)}</select></label>;
 if(c.type==='number') {const target=c.targets.find(t=>t.kind==='node');const n=s.scene?.nodes.find(n=>n.id===target?.id);const prefix=n?(s.authoring?.editorState?.[n.id]?.name||n.id):'';return <NumberField key={c.id} label={prefix?`${prefix} · ${c.label}`:c.label} value={value} min={c.min} max={c.max} step={c.step} onChange={v=>set(c,v)}/>;}
 if(c.type==='text')return <TextField key={c.id} label={c.label} value={value} onChange={v=>set(c,v)}/>;
 if(c.type==='region'||c.type==='image')return <ResourceControl key={c.id} {...{pkg,control:c,commit,notify,onBusy}}/>;
 return null;
 })}</div>;
}

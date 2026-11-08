export const clone = value => structuredClone(value);
export const uid = (prefix='element') => `${prefix}_${crypto.randomUUID().replaceAll('-','').slice(0,16)}`;
export const root = pkg => pkg.config.staruw;
export const nodes = pkg => root(pkg).protocolVersion && root(pkg).protocolVersion!=='1.0.0'?[]:Array.isArray(root(pkg).scene?.nodes)?root(pkg).scene.nodes:[];
export const packageName=pkg=>typeof root(pkg)?.metadata?.name==='string'?root(pkg).metadata.name:'保留的模板';
export function walk(list, visit) {for(const node of list){visit(node);if(node.children)walk(node.children,visit);}}
export function findNode(pkg,id) {let found;walk(nodes(pkg),n=>{if(n.id===id)found=n});return found;}
export const isLocked = (pkg,id) => !!root(pkg).authoring?.editorState?.[id]?.locked;
export function nodeLabel(pkg,n) {return root(pkg).authoring?.editorState?.[n.id]?.name || ({model:'机型区域',parameters:'拍摄参数'}[n.role]) || ({text:'文字',image:'图片',model:'机型字标',group:'图层组'}[n.type]) || n.id;}
export function changePackage(pkg, change) {if(pkg.readOnly)throw Error('此包包含尚不支持的能力，已保留原包，只读打开。');const next={...pkg,config:clone(pkg.config),files:{...pkg.files},dirty:true};change(next.config.staruw,next);return next;}
export function activeControls(pkg) {const s=root(pkg);return(s.controls||[]).filter(c=>(c.when||[]).every(w=>s.defaults[w.controlId]===w.equals));}
export function setControl(pkg,id,value) {return changePackage(pkg,s=>{const c=s.controls.find(c=>c.id===id);if(!c||c.scope!=='template')throw Error('当前控件不属于模板设计');validateControl(c,value);s.defaults[id]=clone(value);});}
function validateControl(c,value) {if(c.type==='number'&&(!Number.isFinite(value)||value<c.min||value>c.max||Math.abs((value-c.min)/c.step-Math.round((value-c.min)/c.step))>1e-7))throw Error(`请输入 ${c.min}–${c.max} 之间、步长为 ${c.step} 的值`);if(c.type==='choice'&&!c.options.some(o=>o.id===value))throw Error('请选择模板声明的款式');if(c.type==='toggle'&&typeof value!=='boolean')throw Error('开关值无效');}
function controlFor(pkg,id,property) {return activeControls(pkg).find(c=>c.scope==='template'&&c.targets.some(t=>t.kind==='node'&&t.id===id&&t.property===property));}
function setControlled(s,c,value) {let val=value;if(c.type==='choice'){const option=c.options.find(o=>JSON.stringify(o.value)===JSON.stringify(value));if(!option)throw Error('这个属性由模板预设控制，请从对应选项中选择');val=option.id;}validateControl(c,val);s.defaults[c.id]=clone(val);}
export function editNode(pkg,id,property,value) {
 if(isLocked(pkg,id))throw Error('请先解锁此元素');
 const c=controlFor(pkg,id,property);
 if(!c&&root(pkg).authoring?.profile!=='authoring.ten-elements.v1')throw Error('此模板仅允许修改已声明的控件');
 return changePackage(pkg,s=>{
  const node=findNode({config:{staruw:s}},id);if(!node)throw Error('元素不存在');
  if(c){setControlled(s,c,value);return;}
  const components={offsetX:['offset',0],offsetY:['offset',1],width:['size',0],height:['size',1]};
  if(components[property]){const [parent,index]=components[property];const parentControl=controlFor(pkg,id,parent);if(parentControl){const stored=s.defaults[parentControl.id];const effective=parentControl.type==='choice'?parentControl.options.find(o=>o.id===stored)?.value:stored;const combined=[...(effective||node.bounds[parent])];combined[index]=value;setControlled(s,parentControl,combined);}else{node.bounds[parent][index]=value;}return;}
  if(property==='offset'||property==='size'){node.bounds[property]=value;return;}
  if(property==='font'){walk([node],n=>{const existing=controlFor(pkg,n.id,'font');if(existing){const controlled=s.controls.find(x=>x.id===existing.id);if(controlled.type==='choice'&&!controlled.options.some(o=>o.value===value))controlled.options.push({id:value,label:value,value});setControlled(s,controlled,value);}else{if(n.fontId)n.fontId=value;if(n.fallbackFontId)n.fallbackFontId=value;}if(n.replacementStyle)n.replacementStyle.fontId=value});return;}
  if(property==='content'){node.content={literal:value};return;}
  if(property.startsWith('style.')){const key=property.slice(6);walk([node],n=>{for(const style of[n.textStyle,n.replacementStyle?.textStyle])if(style){if(key==='size'){const ratio=value/style.size;style.lineHeight*=ratio;style.minSize=Math.min(style.minSize,value);if(style.fontFit){style.fontFit.referenceSize*=ratio;}}style[key]=value;}});return;}
  if(property==='image'){node.resourceId=value;return;}
  node[property]=value;
 });
}
export function setLock(pkg,id,locked) {return changePackage(pkg,s=>{if(!s.authoring)throw Error('此导入模板未声明自由编辑档案');s.authoring.editorState||={};s.authoring.editorState[id]={...s.authoring.editorState[id],locked};});}
export function renameNode(pkg,id,name) {return changePackage(pkg,s=>{if(!s.authoring)return;s.authoring.editorState||={};s.authoring.editorState[id]={...s.authoring.editorState[id],name:name.slice(0,200)};});}
function assertFree(s) {if(s.render?.engine==='xiaomi'||s.authoring?.profile!=='authoring.ten-elements.v1')throw Error('此模板只开放已声明的控件，不能增加自由元素');if(s.scene.nodes.length>=10)throw Error('最多可使用 10 个元素，隐藏和锁定的元素也计入');}
const baseBounds=()=>({reference:'photo',anchor:[.5,.5],pivot:[.5,.5],offset:[0,0],size:[420,90]});
export function addText(pkg,text='你的文字') {return changePackage(pkg,s=>{assertFree(s);const id=uid('text');s.scene.nodes.push({id,type:'text',bounds:baseBounds(),fontId:Object.keys(s.scene.fonts)[0],content:{literal:text},textStyle:{size:40,lineHeight:52,minSize:12,color:'#FFFFFFFF',align:'center',verticalAlign:'center',overflow:'shrink',direction:'ltr'}});});}
export function addImage(pkg,resourceId,aspect=1) {return changePackage(pkg,s=>{assertFree(s);const bounds=baseBounds();bounds.size=[240,240/Math.max(.05,aspect)];s.scene.nodes.push({id:uid('image'),type:'image',bounds,resourceId,fit:'contain',shape:'rect'});});}
export function removeNode(pkg,id) {if(root(pkg).authoring?.profile!=='authoring.ten-elements.v1')throw Error('此模板不允许删除原厂元素');if(isLocked(pkg,id))throw Error('请先解锁此元素');return changePackage(pkg,s=>{const removed=new Set();const selected=s.scene.nodes.find(n=>n.id===id);if(!selected)return;walk([selected],n=>removed.add(n.id));s.scene.nodes=s.scene.nodes.filter(n=>n.id!==id);s.controls=(s.controls||[]).map(c=>({...c,targets:c.targets.filter(t=>!(t.kind==='node'&&removed.has(t.id)))})).filter(c=>c.targets.length);let oldLength;do{oldLength=s.controls.length;const controls=new Set(s.controls.map(c=>c.id));s.controls=s.controls.filter(c=>(c.when||[]).every(w=>controls.has(w.controlId)));}while(oldLength!==s.controls.length);const valid=new Set(s.controls.map(c=>c.id));for(const key of Object.keys(s.defaults))if(!valid.has(key))delete s.defaults[key];for(const key of removed)if(s.authoring?.editorState)delete s.authoring.editorState[key];});}
export function duplicateNode(pkg,id) {return changePackage(pkg,s=>{assertFree(s);const n=s.scene.nodes.find(n=>n.id===id);if(!n||n.type==='group'||n.role)throw Error('预设功能区不能复制，请新增文字或图片');const copy=clone(n);copy.id=uid(n.type);copy.bounds.offset=copy.bounds.offset.map(v=>v+24);s.scene.nodes.splice(s.scene.nodes.indexOf(n)+1,0,copy);});}
export function reorderNode(pkg,id,direction) {if(root(pkg).authoring?.profile!=='authoring.ten-elements.v1')throw Error('此模板不允许重排原厂元素');return changePackage(pkg,s=>{if(isLocked(pkg,id))throw Error('请先解锁此元素');const from=s.scene.nodes.findIndex(n=>n.id===id),to=from+direction;if(to<0||to>=s.scene.nodes.length)return;const [item]=s.scene.nodes.splice(from,1);s.scene.nodes.splice(to,0,item);});}
export function setCanvas(pkg,property,value) {return changePackage(pkg,s=>{const control=activeControls(pkg).find(c=>c.targets.some(t=>t.kind==='canvas'&&t.property===property));if(control)setControlled(s,control,value);else{if(s.authoring?.profile!=='authoring.ten-elements.v1')throw Error('此模板仅允许修改声明的画布控件');s.scene.canvas[property]=clone(value);}});}
export function pushHistory(h,next) {if(next===h.present)return h;return {past:[...h.past,h.present].slice(-40),present:next,future:[]};}
export function undo(h) {return h.past.length?{past:h.past.slice(0,-1),present:h.past.at(-1),future:[h.present,...h.future].slice(0,40)}:h;}
export function redo(h) {return h.future.length?{past:[...h.past,h.present].slice(-40),present:h.future[0],future:h.future.slice(1)}:h;}

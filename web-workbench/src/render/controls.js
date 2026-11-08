import {flattenMetadata} from './metadata.js';
export function controlOperations(config, photoValues={}) {
  const u=config.staruw, operations=[];
  const values={...u.defaults,...photoValues};
  for (const stage of ['layout','design','photo']) for (const control of u.controls || []) {
    if ((control.stage || 'design')!==stage || !(control.when || []).every(c=>values[c.controlId]===c.equals)) continue;
    if (control.scope==='photo' && !Object.hasOwn(photoValues,control.id)) continue;
    let value=values[control.id];
    if (control.type==='choice') {
      const option=control.options.find(o=>o.id===value);
      if (!option) throw new Error(`E_BINDING: 无效控件选项 ${control.id}`);
      value=option.value;
    }
    for(const target of control.targets) operations.push({target, value:structuredClone(value), control});
  }
  return operations;
}
export function applyControls(config, metadata={}, photoValues={}) {
  const u=config.staruw, scene=u.scene ? structuredClone(u.scene) : null, fields=flattenMetadata(metadata), operations=controlOperations(config,photoValues);
  const nodes=new Map();
  function visit(list){for(const node of list || []){nodes.set(node.id,node);visit(node.children);}}
  visit(scene?.nodes);
  // Region replacements block child controls without mutating the native tree.
  const blocked=new Set();
  function blockChildren(node){for(const child of node?.children || []) {blocked.add(child.id);blockChildren(child);}}
  for(const {target,value} of operations) if(target.kind==='node'&&target.property==='modelRegion'&&value?.mode!=='native') blockChildren(nodes.get(target.id));
  for(const {target,value,control} of operations) {
    if(target.kind==='photoField') {fields[target.id]=value;continue;}
    if(target.kind==='xiaomiBinding') continue;
    if(target.kind==='canvas') {if(!scene) continue;scene.canvas[target.property]=value;continue;}
    if(target.kind!=='node') throw new Error('E_BINDING: 未知控件目标');
    if(blocked.has(target.id)) continue;
    const n=nodes.get(target.id); if(!n)throw new Error(`E_BINDING: 目标节点不存在 ${target.id}`);
    switch(target.property){
      case 'visible':case 'opacity':case 'rotation':case 'shape': n[target.property]=value;break;
      case 'offset':case 'size':n.bounds[target.property]=value;break;
      case 'offsetX':n.bounds.offset[0]=value;break;
      case 'offsetY':n.bounds.offset[1]=value;break;
      case 'width':n.bounds.size[0]=value;break;
      case 'height':n.bounds.size[1]=value;break;
      case 'font':if(n.type==='model')n.fallbackFontId=value;else n.fontId=value;break;
      case 'content':n.content=control.type==='text'?{literal:value}:value;break;
      case 'image':n.resourceId=value;break;
      case 'modelRegion':{
        const replacement=control.type==='text'?{mode:'text',text:value,fontId:n.replacementStyle.fontId}:value;
        if(replacement.mode!=='native')n.replacement=replacement;
        break;
      }
      case 'parameterIcon':if(value.mode!=='native'){
        const image=n.type==='group'?n.children.find(c=>c.slot==='parameterIcon'):n;
        if(!image)throw new Error('E_BINDING: 缺少参数图标槽');
        image.resourceId=value.resourceId;image.shape=value.shape;image.fit=value.shape==='circle'?'cover':'contain';image.replacementCircle=value.shape==='circle';
      }break;
      default:throw new Error(`E_BINDING: 未实现的场景操作 ${target.property}`);
    }
  }
  return {scene,metadata:fields,operations};
}

import {need,own,parseJson,treeDigest,sha256,safePath,checkPaths} from './util.js';
import {schema,definition,schemas} from './schema.js';
import {validateResourceBytes,inspectFont} from './resources.js';
import {validateSvg} from './svg.js';
import {decode} from './util.js';

export function resolvePointer(root,pointer) {
  need(typeof pointer==='string'&&/^\/(?:[^~]|~[01])*$/.test(pointer),'E_BINDING','Invalid JSON Pointer',pointer);
  let value=root;
  for(const raw of pointer.split('/').slice(1)){const key=raw.replace(/~1/g,'/').replace(/~0/g,'~');need(value&&typeof value==='object'&&own(value,key),'E_BINDING','JSON Pointer target does not exist',pointer);value=value[key];}
  return value;
}
const projection=value=>JSON.stringify(Object.fromEntries(Object.entries(value).filter(([k])=>!['format','staruw','starmark','portable'].includes(k))));
const sameSet=(a,b)=>a.size===b.size&&[...a].every(x=>b.has(x));
const string=value=>typeof value==='string';
const numeric=(value,min=-1e6,max=1e6)=>typeof value==='number'&&Number.isFinite(value)&&value>=min&&value<=max;
const normalizeModel=value=>value.trim().replace(/\s+/g,' ').replace(/[A-Z]/g,x=>x.toLowerCase());

export async function validateSemantics(config,files) {
  const u=config.staruw,resources=u.resources,space=u.scene??u.xiaomi,fonts=space.fonts,requires=new Set(u.requires),warnings=[],unsupported=[],nodes=new Map(),bindings=new Map(),controls=new Map(),fontInfo=new Map();
  const resource=(id,kind)=>{need(own(resources,id)&&resources[id].kind===kind,'E_RESOURCE',`Missing declared ${kind} resource`);return resources[id];};
  const font=id=>{need(own(fonts,id),'E_RESOURCE','Missing declared font');return fonts[id];};
  need(requires.has(u.render.profile),'E_CAPABILITY','Missing render profile capability','/staruw/requires');
  for(const [id,r] of Object.entries(resources)){
    safePath(r.path);need(own(files,r.path),'E_RESOURCE','Resource file is missing',r.path);need(await sha256(files[r.path])===r.sha256,'E_HASH','Resource digest mismatch',r.path);
    const largeOem=u.render.engine==='xiaomi'&&requires.has('starmark.xiaomi.large-images.v1')&&u.source?.files.some(row=>row.path===r.path&&row.originalPath===r.path);
    const inspected=validateResourceBytes(r,files[r.path],{largeOem});if(r.kind==='font')fontInfo.set(id,inspected);
  }
  need(Object.values(resources).some(r=>r.path===u.metadata.preview&&r.kind==='image'),'E_RESOURCE','Preview must be a declared image','/staruw/metadata/preview');
  // Unselected OEM resources are kept and validated too, including SVGs that
  // were never registered in the new resource table.
  const registered=new Set(Object.values(resources).map(r=>r.path));
  for(const [path,data] of Object.entries(files))if(!registered.has(path)){
    const ext=path.split('.').at(-1).toLowerCase();
    if(ext==='svg')validateSvg(decode(data,path));
    if(['ttf','otf'].includes(ext))inspectFont(data,path);
    if(['png','jpg','jpeg','webp'].includes(ext))validateResourceBytes({kind:'image',path,mediaType:{png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg',webp:'image/webp'}[ext]},data,{largeOem:requires.has('starmark.xiaomi.large-images.v1')&&u.source?.files.some(row=>row.path===path&&row.originalPath===path)});
  }
  function axes(fontId,values) {const f=font(fontId),info=fontInfo.get(f.resourceId);for(const [axis,value] of Object.entries(values)){const range=info.axes[axis];need(range&&numeric(value,range.min,range.max),'E_RESOURCE','Font variation axis is absent or out of range');}}
  const visited=new Set();function visitFont(id,active){font(id);need(!active.has(id),'E_RESOURCE','Font fallback cycle');if(visited.has(id))return;active.add(id);for(const child of fonts[id].fallbackFontIds)visitFont(child,active);active.delete(id);visited.add(id);}
  for(const [id,f] of Object.entries(fonts)){resource(f.resourceId,'font');axes(id,f.axes);visitFont(id,new Set());}
  for(const id of Object.values(space.catalogs)){
    const r=resource(id,'catalog'),catalog=schema('catalog',parseJson(files[r.path],r.path),r.path),seen=new Set();
    for(const row of catalog.entries){const key=`${normalizeModel(row.make??'')}\0${normalizeModel(row.model)}`;need(!seen.has(key),'E_BINDING','Duplicate normalized model match',r.path);seen.add(key);for(const component of row.components)resource(component.resourceId,'image');}
  }
  if(Object.keys(space.catalogs).length)need(requires.has('scene.model-catalog.v1'),'E_CAPABILITY','Model catalogs require their capability');
  if(u.source){
    const source=u.source,original=Object.create(null),destinations=new Set();
    checkPaths(source.files.map(row=>({path:row.originalPath})));
    for(const row of source.files){safePath(row.path);need(!own(original,row.originalPath)&&!destinations.has(row.path)&&own(files,row.path),'E_RESOURCE','Duplicate or missing source mapping');original[row.originalPath]=files[row.path];destinations.add(row.path);}
    need(await treeDigest(original)===source.sourceDigest,'E_HASH','Preserved source digest mismatch','/staruw/source');
    need(source.files.some(row=>row.originalPath===source.entry&&row.path===source.config),'E_RESOURCE','Source entry is not mapped');
    if(source.kind.endsWith('-sanitized')){need(source.sanitized===true&&source.removedFields?.length,'E_SCHEMA','Incomplete sanitized source provenance');need(!source.files.some(row=>row.originalPath==='manifest.json'),'E_SCHEMA','Sanitized source cannot retain the old manifest');}
    if(source.kind==='xiaomi-oem'||u.render.engine==='xiaomi')need(source.vendor==='xiaomi','E_BINDING','Xiaomi source vendor mismatch');
    if(u.render.engine==='xiaomi'){
      need(source.entry==='config.json','E_BINDING','Invalid Xiaomi source entry');const originalConfig=parseJson(files[source.config],source.config);
      need(originalConfig&&typeof originalConfig==='object'&&projection(originalConfig)===projection(config),'E_HASH','Active OEM tree diverges from preserved source','/staruw/source');
      need(source.schemaVersion===(Number.isInteger(config.version)?`${config.version}.0`:String(config.version)),'E_BINDING','Source version does not match OEM version');
    }
  }
  function style(value){need(value.minSize<=value.size,'E_BINDING','Minimum text size exceeds text size');if(value.fontFit){font(value.fontFit.referenceFontId);axes(value.fontFit.referenceFontId,value.fontFit.referenceAxes);}}
  function content(value){definition('content',value);for(const part of value.parts??[])if(part.field){const allowed={plain:schemas.config.$defs.field.enum,focalMm:['exif.focalLengthMm','exif.focalLength35mm'],apertureF:['exif.aperture'],shutterSeconds:['exif.exposureSeconds'],iso:['exif.iso'],gpsDms:['gps.latitude','gps.longitude'],dateTimeDots:['capture.localTime'],dateTimeSlash:['capture.localTime']};need(allowed[part.format]?.includes(part.field),'E_BINDING','Dynamic field does not match formatter');}}
  function background(value){definition('background',value);if(value.kind==='image')resource(value.resourceId,'image');if(value.kind==='material')need(own(space.materials??{},value.materialId),'E_RESOURCE','Unknown background material');}
  if(u.scene){
    function walk(children,depth){need(depth<=64,'E_LIMIT','Scene exceeds 64 levels');for(const n of children){need(!nodes.has(n.id),'E_BINDING','Duplicate scene node ID');nodes.set(n.id,n);need(nodes.size<=4096,'E_LIMIT','Scene node budget exceeded');need((n.bounds.reference==='parent')===(depth>1),'E_BINDING','Invalid parent coordinate reference');
      if(n.visible!==false&&n.bounds.reference.startsWith('frame')){const edge=n.bounds.reference.slice(5).toLowerCase();need(space.canvas.insets[edge]>0,'E_BINDING','Visible node references an empty frame');}
      if(n.type==='image'){resource(n.resourceId,'image');need(n.shape!=='circle'||n.bounds.size[0]===n.bounds.size[1],'E_BINDING','Circular image bounds must be square');}
      if(n.type==='text'){font(n.fontId);style(n.textStyle);content(n.content);}
      if(n.type==='model'){font(n.fallbackFontId);style(n.textStyle);need(own(space.catalogs,n.catalogId),'E_RESOURCE','Missing model catalog');need(requires.has('scene.model-catalog.v1'),'E_CAPABILITY','Missing model capability');}
      if(n.type==='group'){if(n.role==='model'){need(n.replacementStyle,'E_BINDING','Model group requires replacement style');font(n.replacementStyle.fontId);style(n.replacementStyle.textStyle);}walk(n.children,depth+1);}
    }}walk(space.nodes,1);
    need(space.canvas.mode!=='overlay'||Object.values(space.canvas.insets).every(x=>x===0),'E_BINDING','Overlay canvas cannot have frame insets');background(space.canvas.background);
    for(const [id,m] of Object.entries(space.materials)){
      if(m.kind==='photo-blur'||m.fallback?.kind==='photo-blur')need(requires.has('material.photo-blur.v1'),'E_CAPABILITY','Missing blur capability');
      if(m.kind==='photo-glass')warnings.push({code:'W_MATERIAL_FALLBACK',severity:'warning',path:`/staruw/scene/materials/${id}`,message:'The declared glass fallback is used; the original recipe is retained.'});
    }
    if(u.authoring?.profile==='authoring.ten-elements.v1'){
      need(space.nodes.length<=10,'E_LIMIT','Ten-element authoring profile exceeds ten elements');const roles=new Set();
      for(const node of space.nodes)if(node.type==='group'){
        need(['model','parameters'].includes(node.role)&&!roles.has(node.role),'E_BINDING','Unregistered or duplicate preset group');roles.add(node.role);const children=node.children;
        if(node.role==='model')need(children.length<=2&&children.filter(x=>x.type==='model').length===1&&children.every(x=>x.type==='model'||x.type==='image'&&x.slot==='brand'),'E_BINDING','Invalid model preset children');
        else need(children.filter(x=>x.type==='image').length<=1&&children.every(x=>x.type==='image'?x.slot==='parameterIcon':x.type==='text'&&x.slot==='parameters'&&x.content.parts?.some(p=>/^(exif|capture|gps)\./.test(p.field??''))),'E_BINDING','Invalid parameter preset children');
      }
    }
  }
  if(u.render.engine==='xiaomi'){
    let nativeNodes=0;
    function nativeTree(children,depth){need(Array.isArray(children),'E_SCHEMA','OEM childViews must be an array');need(depth<=64,'E_LIMIT','OEM tree exceeds 64 levels');for(const node of children){need(node&&typeof node==='object'&&!Array.isArray(node),'E_SCHEMA','OEM layout node must be an object');need(++nativeNodes<=4096,'E_LIMIT','OEM node budget exceeded');if(own(node,'childViews'))nativeTree(node.childViews,depth+1);}}
    for(const group of config.layout_group)for(const candidate of Object.values(group))if(Array.isArray(candidate))nativeTree(candidate,1);
    need(requires.has('starmark.xiaomi.v1'),'E_CAPABILITY','Missing Xiaomi product policy');for(const id of [...Object.values(space.fontAliases),...Object.values(space.fontPolicy)])font(id);
    const layout=selection=>{need(Array.isArray(config.layout_group?.[selection.groupIndex]?.[selection.key]),'E_BINDING','Unknown Xiaomi layout selection');};layout(space.initialLayout);
    for(const binding of space.bindings){need(!bindings.has(binding.id),'E_BINDING','Duplicate Xiaomi binding ID');bindings.set(binding.id,binding);const pointer=binding.sourcePointer;
      need(!/^\/(staruw|source|format)(?:\/|$)/.test(pointer),'E_BINDING','Cannot bind retained or core protocol data');const node=resolvePointer(config,pointer),prop=binding.property;
      need(binding.nodeType==='LayoutGroup'?pointer==='/layout_group'&&Array.isArray(node):node&&typeof node==='object'&&(node.type===binding.nodeType||node.viewType===binding.nodeType),'E_BINDING','Xiaomi binding node type mismatch',pointer);
      for(const operation of binding.operations){
        const isNode=node&&!Array.isArray(node)&&typeof node==='object';
        const modelRun=isNode&&node.type==='Layout'&&(node.childViews??[]).filter(x=>x.type==='TextView'&&String(x.text??'').startsWith('@model=')).length===2&&(node.childViews??[]).filter(x=>x.type==='ImageView'&&String(x.path??'').includes('path=${logo}')).length===1;
        const valid={visible:isNode&&node.optional===true&&prop==='visible',scalePreset:isNode&&node.support_scale===true&&prop==='scale',font:isNode&&node.type==='TextView'&&string(node.font_ref)&&prop==='font',modelRegion:isNode&&(modelRun||node.type==='ImageView'&&node.id==='img_model')&&prop==='model',parameterIcon:isNode&&node.type==='ImageView'&&['leica','brand_leica'].includes(node.id)&&/path=(?:leica(?:_p3|_black|_white)?\.webp|Mark[CS]\.svg)(?:;|$)/.test(node.path??'')&&prop==='parameterIcon',photoOverride:schemas.config.$defs.field.enum.includes(prop),selectVariant:prop==='layout'&&binding.nodeType==='LayoutGroup'||isNode&&(prop==='background'&&node.background_optional===true||/^(customizeSettings|mix|mixText)\./.test(prop)||['glassBackground','caption'].includes(prop))}[operation];
        need(valid,'E_BINDING','Operation exceeds declared OEM capability',pointer);
      }
      const variants=new Set();for(const variant of binding.variants??[]){need(!variants.has(variant.id),'E_BINDING','Duplicate Xiaomi variant');variants.add(variant.id);if(variant.selection.kind==='layout'){need(prop==='layout','E_BINDING','Layout variant requires layout binding');layout(variant.selection);}else {
        // Proprietary option membership needs the exact OEM adapter. A safe
        // unrecognized option is retained but can never become editable here.
        unsupported.push({code:'E_CAPABILITY',severity:'error',path:pointer,message:'This OEM option needs an audited native adapter.',capability:'xiaomi.option.membership'});
      }}
      if(binding.replacementStyle){font(binding.replacementStyle.fontId);style(binding.replacementStyle.textStyle);}
    }
    // Check every literal @file/@img dependency and raw rendering path, without
    // evaluating template expressions. Known selector placeholders are handled
    // by the renderer's supported-capability inspection.
    function oemRefs(value,key='',depth=0){need(depth<=128,'E_LIMIT','OEM configuration depth exceeds budget');if(Array.isArray(value)){for(const child of value)oemRefs(child,key,depth+1);}else if(value&&typeof value==='object'){for(const [k,v] of Object.entries(value))oemRefs(v,k,depth+1);}else if(string(value)){
      const names=[];for(const match of value.matchAll(/@(?:file|img)=([^;]+)|(?:^|;)path=([^;]+)/g))names.push(match[1]??match[2]);if(['path','path_mivi'].includes(key)&&value&&!value.startsWith('@'))names.push(value);
      for(const name of names){if(!name||name.includes('${'))continue;safePath(name);if(key==='icon'&&!own(files,name)){warnings.push({code:'W_PICKER_ICON',severity:'warning',path:name,message:'OEM picker icon is absent; this does not affect rendered output.'});continue;}need(own(files,name),'E_RESOURCE','Missing OEM render dependency',name);}
    }}oemRefs(Object.fromEntries(Object.entries(config).filter(([k])=>k!=='staruw')));
  }
  function replacement(c,value,model){need(value&&typeof value==='object'&&!Array.isArray(value),'E_BINDING','Replacement must be an object');const mode=value.mode,allowed=c?(model?c.allowModes:['native','image']):(model?['native','text','image']:['native','image']);need(allowed.includes(mode),'E_BINDING','Unsupported replacement mode');const keys=mode==='native'?['mode']:mode==='text'?['mode','text','fontId']:model?['mode','resourceId','fit']:['mode','resourceId','shape'];need(sameSet(new Set(keys),new Set(Object.keys(value))),'E_BINDING','Unexpected replacement fields');
    if(mode==='text'){need(string(value.text)&&value.text.length<=65536,'E_BINDING','Invalid signature text');font(value.fontId);}
    if(mode==='image'){const r=resource(value.resourceId,'image'),key=model?'fit':'shape',accepted=c?c[model?'allowFits':'allowShapes']:model?['contain','cover']:['rect','circle'];need(accepted.includes(value[key])&&(!c||c.allowTypes.includes(r.mediaType)),'E_BINDING','Replacement format is not allowed');}
  }
  function photoValue(field,value){if(field.startsWith('exif.'))need(numeric(value,Number.MIN_VALUE,1e6)&&(field!=='exif.iso'||Number.isInteger(value)),'E_BINDING','Invalid exposure value');else if(field.startsWith('gps.'))need(numeric(value,field==='gps.latitude'?-90:-180,field==='gps.latitude'?90:180),'E_BINDING','Invalid coordinate');else {need(string(value)&&value.length<=65536,'E_BINDING','Invalid photo text');if(field==='capture.localTime'&&value){const m=/^(\d{4})[-:](\d{2})[-:](\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$/.exec(value);need(m,'E_BINDING','Invalid capture time');const n=m.slice(1).map(Number),d=new Date(0);d.setUTCFullYear(n[0],n[1]-1,n[2]);d.setUTCHours(n[3],n[4],n[5],0);need(d.getUTCFullYear()===n[0]&&d.getUTCMonth()===n[1]-1&&d.getUTCDate()===n[2]&&d.getUTCHours()===n[3]&&d.getUTCMinutes()===n[4]&&d.getUTCSeconds()===n[5],'E_BINDING','Capture time out of range');}}}
  function targetValue(c,t,value){const p=t.property;if(t.kind==='photoField'){photoValue(t.id,value);return;}
    if(p==='modelRegion'||p==='parameterIcon'){if(c.type==='text'){need(p==='modelRegion'&&string(value),'E_BINDING','Text control requires model region');return;}replacement(['region','image'].includes(c.type)?c:null,value,p==='modelRegion');return;}
    if(t.kind==='xiaomiBinding'){
      const binding=bindings.get(t.id);if(p==='selectVariant'){need(c.type==='choice'&&binding.variants.some(x=>x.id===value),'E_BINDING','Choice is not a registered native variant');return;}
      if(p==='scalePreset'){need(['small','medium','large'].includes(value),'E_BINDING','Invalid native scale preset');return;}
      if(p==='photoOverride'){need(c.scope==='photo','E_SCOPE','Photo override cannot be template state');photoValue(binding.property,value);return;}
    }
    switch(p){
      case 'visible':need(typeof value==='boolean','E_BINDING','Visibility must be boolean');break;
      case 'opacity':need(numeric(value,0,1),'E_BINDING','Opacity is out of range');break;
      case 'rotation':need(numeric(value,-360,360),'E_BINDING','Rotation is out of range');break;
      case 'offsetX':case 'offsetY':need(numeric(value),'E_BINDING','Offset is out of range');break;
      case 'width':case 'height':need(numeric(value,Number.MIN_VALUE),'E_BINDING','Size must be positive');break;
      case 'offset':case 'size':definition(p==='offset'?'point':'positivePoint',value);break;
      case 'font':font(value);break;
      case 'content':if(c.type==='text')need(string(value),'E_BINDING','Literal text must be a string');else content(value);break;
      case 'image':resource(value,'image');break;
      case 'shape':need(['rect','circle'].includes(value),'E_BINDING','Invalid image shape');break;
      case 'background':background(value);break;
      case 'insets':definition('insets',value);need(space.canvas.mode!=='overlay'||Object.values(value).every(x=>x===0),'E_BINDING','Overlay cannot gain frame insets');break;
      default:need(false,'E_BINDING','Unregistered target operation');
    }
  }
  for(const c of u.controls){need(!controls.has(c.id),'E_BINDING','Duplicate control ID');controls.set(c.id,c);}
  need(sameSet(new Set(Object.keys(u.defaults)),new Set(u.controls.filter(c=>c.scope==='template').map(c=>c.id))),'E_SCOPE','Defaults must exactly cover template controls');
  const writers=new Map();
  for(const c of u.controls){const stage=c.stage??'design';need((stage==='photo')===(c.scope==='photo'),'E_SCOPE','Photo scope and stage must match');if(stage==='layout')need(c.type==='choice'&&!c.when?.length,'E_BINDING','Layout control must be an unconditional choice');
    for(const condition of c.when??[]){const parent=controls.get(condition.controlId);need(parent?.stage==='layout'&&parent.type==='choice'&&parent.options.some(o=>o.id===condition.equals),'E_BINDING','Invalid layout dependency');}
    const values=[];
    if(c.type==='choice'){const options=new Set();for(const o of c.options){need(!options.has(o.id),'E_BINDING','Duplicate choice ID');options.add(o.id);values.push(o.value);}if(c.scope==='template')need(options.has(u.defaults[c.id]),'E_BINDING','Unknown default choice');}
    if(c.type==='number')need(c.min<=c.max&&c.step>0,'E_BINDING','Invalid number control interval');
    if(c.scope==='template'&&c.type!=='choice'){const value=u.defaults[c.id];if(c.type==='toggle')need(typeof value==='boolean','E_BINDING','Invalid toggle default');else if(c.type==='number'){need(numeric(value,c.min,c.max)&&Math.abs((value-c.min)/c.step-Math.round((value-c.min)/c.step))<=1e-9,'E_BINDING','Invalid number default');}else if(c.type==='text')need(string(value)&&value.length<=65536,'E_BINDING','Invalid text default');else replacement(c,value,c.type==='region');values.push(value);}
    if(c.type==='number')values.push(c.min,c.max);if(c.type==='toggle')values.push(true);if(c.type==='text')values.push('');
    for(const t of c.targets){const p=t.property,key=`${t.kind}/${t.id}`,props=writers.get(key)??new Set(),overlaps={offset:['offsetX','offsetY'],offsetX:['offset'],offsetY:['offset'],size:['width','height'],width:['size'],height:['size']}[p]??[];need(!props.has(p)&&!overlaps.some(x=>props.has(x)),'E_BINDING','Multiple controls write overlapping properties');props.add(p);writers.set(key,props);
      if(c.type==='region')need(p==='modelRegion','E_BINDING','Region control requires modelRegion target');if(c.type==='image')need(p==='parameterIcon','E_BINDING','Image control requires parameterIcon target');if(c.type==='text')need(['content','modelRegion'].includes(p)||c.scope==='photo'&&['value','photoOverride'].includes(p),'E_BINDING','Text control target is incompatible');
      if(t.kind==='node'){const n=nodes.get(t.id);need(n,'E_BINDING','Control node target is missing');if(p==='font')need(['text','model'].includes(n.type),'E_BINDING','Font target is not text');if(p==='content')need(n.type==='text'&&own(n.content,'literal'),'E_BINDING','Control cannot replace dynamic photo content');if(['image','shape'].includes(p))need(n.type==='image','E_BINDING','Image target is not an image');if(p==='modelRegion')need(n.type==='group'&&n.role==='model','E_BINDING','Region target is not model group');if(p==='parameterIcon')need(n.type==='image'&&n.slot==='parameterIcon'||n.type==='group'&&n.role==='parameters'&&n.children.filter(x=>x.type==='image'&&x.slot==='parameterIcon').length===1,'E_BINDING','Target lacks a dedicated parameter icon');}
      else if(t.kind==='xiaomiBinding'){need(bindings.has(t.id)&&bindings.get(t.id).operations.includes(p),'E_BINDING','Native operation is not authorized');if(p==='photoOverride')need(c.scope==='photo','E_SCOPE','Photo override cannot be template state');}
      else if(t.kind==='canvas')need(u.scene&&t.id==='canvas'&&['background','insets'].includes(p),'E_BINDING','Invalid canvas target');
      else {need(c.scope==='photo'&&p==='value','E_SCOPE','Photo target requires photo scope');definition('field',t.id);}
      for(const value of values)targetValue(c,t,value);
    }
  }
  return {warnings,unsupported,nodes,bindings};
}
